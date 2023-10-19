package com.futo.platformplayer.states

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract.EXTRA_INITIAL_URI
import androidx.activity.ComponentActivity
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.futo.platformplayer.R
import com.futo.platformplayer.Settings
import com.futo.platformplayer.UIDialogs
import com.futo.platformplayer.activities.IWithResultLauncher
import com.futo.platformplayer.activities.MainActivity
import com.futo.platformplayer.activities.SettingsActivity
import com.futo.platformplayer.api.media.models.video.SerializedPlatformVideo
import com.futo.platformplayer.copyTo
import com.futo.platformplayer.copyToOutputStream
import com.futo.platformplayer.encryption.EncryptionProvider
import com.futo.platformplayer.getInputStream
import com.futo.platformplayer.getNowDiffHours
import com.futo.platformplayer.getOutputStream
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.readBytes
import com.futo.platformplayer.stores.FragmentedStorage
import com.futo.platformplayer.stores.v2.ManagedStore
import com.futo.platformplayer.writeBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.IllegalStateException

class StateBackup {
    companion object {
        val TAG = "StateBackup";

        private val _autoBackupLock = Object();

        private fun getAutomaticBackupDocumentFiles(context: Context, create: Boolean = false): Pair<DocumentFile?, DocumentFile?> {
            if(!Settings.instance.storage.isStorageMainValid(context))
                return Pair(null, null);
            val uri = Settings.instance.storage.getStorageGeneralUri() ?: return Pair(null, null);
            val dir = DocumentFile.fromTreeUri(context, uri) ?: return Pair(null, null);
            val mainBackupFile = dir.findFile("GrayjayBackup.ezip") ?: if(create) dir.createFile("grayjay/ezip", "GrayjayBackup.ezip") else null;
            val secondaryBackupFile = dir.findFile("GrayjayBackup.ezip.old") ?: if(create) dir.createFile("grayjay/ezip", "GrayjayBackup.ezip.old") else null;
            return Pair(mainBackupFile, secondaryBackupFile);
        }
        /*
        private fun getAutomaticBackupFiles(): Pair<File, File> {
            val dir = StateApp.instance.getExternalRootDirectory();
            if(dir == null)
                throw IllegalStateException("Can't access external files");
            return Pair(File(dir, "GrayjayBackup.ezip"), File(dir, "GrayjayBackup.ezip.old"))
        }*/


        fun getAllMigrationStores(): List<ManagedStore<*>> = listOf(
            StateSubscriptions.instance.toMigrateCheck(),
            StatePlaylists.instance.toMigrateCheck()
        ).flatten();


        private fun getAutomaticBackupPassword(customPassword: String? = null): String {
            val password = customPassword ?: Settings.instance.backup.autoBackupPassword ?: "";
            val pbytes = password.toByteArray();
            if(pbytes.size < 4 || pbytes.size > 32)
                throw IllegalStateException("Automatic backup passwords should atleast be 4 character and smaller than 32");
            return password.padStart(32, '9');
        }
        fun hasAutomaticBackup(): Boolean {
            val context = StateApp.instance.contextOrNull ?: return false;
            if(!Settings.instance.storage.isStorageMainValid(context))
                return false;
            val files = getAutomaticBackupDocumentFiles(context,);
            return files.first?.exists() ?: false || files.second?.exists() ?: false;
        }
        fun startAutomaticBackup(force: Boolean = false) {
            val lastBackupHoursAgo = Settings.instance.backup.lastAutoBackupTime.getNowDiffHours();
            if(!force && lastBackupHoursAgo < 24) {
                Logger.i(TAG, "Not AutoBackuping, last backup ${lastBackupHoursAgo} hours ago");
                return;
            }

            StateApp.instance.scopeOrNull?.launch(Dispatchers.IO){
                try {
                    Logger.i(TAG, "Starting AutoBackup (Last ${lastBackupHoursAgo} ago)");
                    synchronized(_autoBackupLock) {
                        val context = StateApp.instance.contextOrNull ?: return@synchronized;
                        val data = export();
                        val zip = data.asZip();

                        val encryptedZip = EncryptionProvider.instance.encrypt(zip, getAutomaticBackupPassword());

                        if(!Settings.instance.storage.isStorageMainValid(context)) {
                            StateApp.instance.scopeOrNull?.launch(Dispatchers.Main) {
                                UIDialogs.toast("Missing permissions for auto-backup, please set the external general directory in settings");
                            }
                        }
                        else {
                            val backupFiles = getAutomaticBackupDocumentFiles(context, true);
                            val exportFile = backupFiles.first;
                            if (exportFile?.exists() == true && backupFiles.second != null)
                                exportFile!!.copyTo(context, backupFiles.second!!);
                            exportFile!!.writeBytes(context, encryptedZip);

                            Settings.instance.backup.lastAutoBackupTime = OffsetDateTime.now(); //OffsetDateTime.now();
                            Settings.instance.save();
                        }
                    }
                    Logger.i(TAG, "Finished AutoBackup");
                }
                catch(ex: Throwable) {
                    Logger.e(TAG, "Failed to AutoBackup", ex);
                    StateApp.instance.scopeOrNull?.launch(Dispatchers.Main) {
                        UIDialogs.toast("Failed to auto backup:\n" + ex.message);
                    };
                }
            }
        }

        //TODO: This goes has recently changed to use DocumentFiles and DocumentTree, and might need additional checks/edgecases covered.
        fun restoreAutomaticBackup(context: Context, scope: CoroutineScope, password: String, ifExists: Boolean = false) {
            if(ifExists && !hasAutomaticBackup()) {
                Logger.i(TAG, "No AutoBackup exists, not restoring");
                return;
            }

            Logger.i(TAG, "Starting AutoBackup restore");
            synchronized(_autoBackupLock) {

                val backupFiles = getAutomaticBackupDocumentFiles(StateApp.instance.context, false);
                try {
                    if (backupFiles.first?.exists() != true)
                        throw IllegalStateException("Backup file does not exist");

                    val backupBytesEncrypted = backupFiles.first!!.readBytes(context) ?: throw IllegalStateException("Could not read stream of [${backupFiles.first?.uri}]");
                    val backupBytes = EncryptionProvider.instance.decrypt(backupBytesEncrypted, getAutomaticBackupPassword(password));
                    importZipBytes(context, scope, backupBytes);
                    Logger.i(TAG, "Finished AutoBackup restore");
                }
                catch (exSec: FileNotFoundException) {
                    Logger.e(TAG, "Failed to access backup file", exSec);
                    val activity = if(SettingsActivity.getActivity() != null)
                        SettingsActivity.getActivity();
                    else if(StateApp.instance.isMainActive)
                        StateApp.instance.contextOrNull;
                    else null;
                    if(activity != null) {
                        if(activity is IWithResultLauncher)
                            StateApp.instance.requestDirectoryAccess(activity, "Grayjay Backup Directory", "Allows restoring of a backup", backupFiles.first?.parentFile?.uri) {
                                if(it != null) {
                                    val customFiles = StateBackup.getAutomaticBackupDocumentFiles(activity);
                                    if(customFiles.first != null && customFiles.first!!.isFile && customFiles.first!!.exists() && customFiles.first!!.canRead())
                                        restoreAutomaticBackup(context, scope, password, ifExists);
                                }
                            };
                    }
                }
                catch (ex: Throwable) {
                    Logger.e(TAG, "Failed main AutoBackup restore", ex)
                    if (backupFiles.second?.exists() != true)
                        throw ex;

                    val backupBytesEncrypted = backupFiles.second!!.readBytes(context) ?: throw IllegalStateException("Could not read stream of [${backupFiles.second?.uri}]");
                    val backupBytes = EncryptionProvider.instance.decrypt(backupBytesEncrypted, getAutomaticBackupPassword(password));
                    importZipBytes(context, scope, backupBytes);
                    Logger.i(TAG, "Finished AutoBackup restore");
                }
            }
        }

        fun startExternalBackup() {
            val data = export();
            val now = OffsetDateTime.now();
            val exportFile = File(
                FragmentedStorage.getOrCreateDirectory("shares"),
                "export_${now.year}-${now.monthValue.toString().padStart(2, '0')}-${now.dayOfMonth.toString().padStart(2, '0')}_${now.hour.toString().padStart(2, '0')}${now.minute.toString().padStart(2, '0')}.zip");
            exportFile.writeBytes(data.asZip());

            StateApp.instance.contextOrNull?.let {
                val uri = FileProvider.getUriForFile(it, it.resources.getString(R.string.authority), exportFile);

                val activity = SettingsActivity.getActivity() ?: return@let;
                activity.startActivity(
                    ShareCompat.IntentBuilder(activity)
                        .setType("application/zip")
                        .setStream(uri)
                        .intent);
            }
        }

        fun export(): ExportStructure {
            val exportInfo = mapOf(
                Pair("version", "1")
            );
            val storesToSave = getAllMigrationStores()
                .associateBy { it.name }
                .mapValues { it.value.getAllReconstructionStrings() }
                .toMutableMap();
            val settings = Settings.instance.encode();
            val pluginSettings = StatePlugins.instance.getPlugins()
                .associateBy { it.config.id }
                .mapValues { it.value.settings };
            val pluginUrls = StatePlugins.instance.getPlugins()
                .filter { it.config.sourceUrl != null }
                .associateBy { it.config.id }
                .mapValues { it.value.config.sourceUrl!! };


            val export = ExportStructure(exportInfo, settings, storesToSave, pluginUrls, pluginSettings);
            //export.videoCache = StatePlaylists.instance.getHistory()
            //    .distinctBy { it.video.url }
            //    .map { it.video };
            return export;
        }


        fun importZipBytes(context: Context, scope: CoroutineScope, zipData: ByteArray) {
            val import: StateBackup.ExportStructure;
            try {
                ByteArrayInputStream(zipData).use {
                    ZipInputStream(it).use {
                        import = StateBackup.ExportStructure.fromZip(it);
                    }
                }
            }
            catch(ex: Throwable) {
                UIDialogs.showGeneralErrorDialog(context, "Failed to import zip", ex);
                return;
            }
            import(context, scope, import);
        }
        fun import(context: Context, scope: CoroutineScope, export: ExportStructure) {

            val availableStores = getAllMigrationStores();
            val unknownPlugins = export.plugins.filter { !StatePlugins.instance.hasPlugin(it.key) };

            var doImport = false;
            var doImportSettings = false;
            var doImportPlugins = false;
            var doImportPluginSettings = false;
            var doEnablePlugins = false;
            var doImportStores = false;
            Logger.i(TAG, "Starting import choices");
            UIDialogs.multiShowDialog(context, {
                Logger.i(TAG, "Starting import");
                if(!doImport)
                    return@multiShowDialog;
                val enabledBefore = StatePlatform.instance.getEnabledClients().map { it.id };

                val onConclusion = {
                    scope.launch(Dispatchers.IO) {
                        StatePlatform.instance.selectClients(*enabledBefore.toTypedArray());

                        withContext(Dispatchers.Main) {
                            UIDialogs.showDialog(context, R.drawable.ic_update_success_251dp,
                                "Import has finished", null, null, 0, UIDialogs.Action("Ok", {}));
                        }
                    }
                };
                //TODO: Probably restructure this to be less nested
                scope.launch(Dispatchers.IO) {
                    try {
                        if (doImportSettings && export.settings != null) {
                            Logger.i(TAG, "Importing settings");
                            try {
                                Settings.replace(export.settings);
                            }
                            catch(ex: Throwable) {
                                UIDialogs.toast(context, "Failed to import settings\n(" + ex.message + ")");
                            }
                        }

                        val afterPluginInstalls = {
                            scope.launch(Dispatchers.IO) {
                                if (doEnablePlugins) {
                                    val availableClients = StatePlatform.instance.getEnabledClients().toMutableList();
                                    availableClients.addAll(StatePlatform.instance.getAvailableClients().filter { !availableClients.contains(it) });

                                    Logger.i(TAG, "Import enabling plugins [${availableClients.map{it.name}.joinToString(", ")}]");
                                    StatePlatform.instance.updateAvailableClients(context, false);
                                    StatePlatform.instance.selectClients(*availableClients.map { it.id }.toTypedArray());
                                }
                                if(doImportPluginSettings) {
                                    for(settings in export.pluginSettings) {
                                        Logger.i(TAG, "Importing Plugin settings [${settings.key}]");
                                        StatePlugins.instance.setPluginSettings(settings.key, settings.value);
                                    }
                                }
                                val toAwait = export.stores.map { it.key }.toMutableList();
                                if(doImportStores) {
                                    for(store in export.stores) {
                                        Logger.i(TAG, "Importing store [${store.key}]");
                                        val relevantStore = availableStores.find { it.name == store.key };
                                        if(relevantStore == null) {
                                            Logger.w(TAG, "Unknown store [${store.key}] import");
                                            continue;
                                        }
                                        withContext(Dispatchers.Main) {
                                            UIDialogs.showImportDialog(context, relevantStore, store.key, store.value) {
                                                synchronized(toAwait) {
                                                    toAwait.remove(store.key);
                                                    if(toAwait.isEmpty())
                                                        onConclusion();
                                                }
                                            };
                                        }
                                    }
                                }
                            }
                        }

                        if (doImportPlugins) {
                            Logger.i(TAG, "Importing plugins");
                            StatePlugins.instance.installPlugins(context, scope, unknownPlugins.map { it.value }) {
                                afterPluginInstalls();
                            }
                        }
                        else
                            afterPluginInstalls();
                    }
                    catch(ex: Throwable) {
                        Logger.e(TAG, "Import failed", ex);
                        UIDialogs.showGeneralErrorDialog(context, "Import failed", ex);
                    }
                }
            },
                UIDialogs.Descriptor(R.drawable.ic_move_up,
                    "Do you want to import data?",
                    "Several dialogs will follow asking individual parts",
                    "Settings: ${export.settings != null}\n" +
                            "Plugins: ${unknownPlugins.size}\n" +
                            "Plugin Settings: ${export.pluginSettings.size}\n" +
                            export.stores.map { "${it.key}: ${it.value.size}" }.joinToString("\n").trim()
                    , 1,
                    UIDialogs.Action("Import", {
                        doImport = true;
                    }, UIDialogs.ActionStyle.PRIMARY), UIDialogs.Action("Cancel", { doImport = false})
                ),
                if(export.settings != null) UIDialogs.Descriptor(R.drawable.ic_settings,
                    "Would you like to import settings",
                    "These are the settings that configure how your app works",
                    null, 0,
                    UIDialogs.Action("Yes", {
                        doImportSettings = true;
                    }, UIDialogs.ActionStyle.PRIMARY), UIDialogs.Action("No", {})
                ).withCondition { doImport } else null,
                if(unknownPlugins.isNotEmpty()) UIDialogs.Descriptor(R.drawable.ic_sources,
                    "Would you like to import plugins?",
                    "Your import contains the following plugins",
                    unknownPlugins.map { it.value }.joinToString("\n"), 1,
                    UIDialogs.Action("Yes", {
                        doImportPlugins = true;
                    }, UIDialogs.ActionStyle.PRIMARY), UIDialogs.Action("No", {})
                ).withCondition { doImport } else null,
                if(export.pluginSettings.isNotEmpty()) UIDialogs.Descriptor(R.drawable.ic_sources,
                    "Would you like to import plugin settings?",
                    null, null, 1,
                    UIDialogs.Action("Yes", {
                        doImportPluginSettings = true;
                    }, UIDialogs.ActionStyle.PRIMARY), UIDialogs.Action("No", {})
                ).withCondition { doImport } else null,
                UIDialogs.Descriptor(R.drawable.ic_sources,
                    "Would you like to enable all plugins?",
                    "Enabling all plugins ensures all required plugins are available during import",
                    null, 0,
                    UIDialogs.Action("Yes", {
                        doEnablePlugins = true;
                    }, UIDialogs.ActionStyle.PRIMARY), UIDialogs.Action("No", {})
                ).withCondition { doImport },
                if(export.stores.isNotEmpty()) UIDialogs.Descriptor(R.drawable.ic_move_up,
                    "Would you like to import stores",
                    "Stores contain playlists, watch later, subscriptions, etc",
                    null, 0,
                    UIDialogs.Action("Yes", {
                        doImportStores = true;
                    }, UIDialogs.ActionStyle.PRIMARY), UIDialogs.Action("No", {})
                ).withCondition { doImport } else null
            );
        }
    }

    class ExportStructure(
        val exportInfo: Map<String, String>,
        val settings: String?,
        val stores: Map<String, List<String>>,
        val plugins: Map<String, String>,
        val pluginSettings: Map<String, Map<String, String?>>,
    ) {
        var videoCache: List<SerializedPlatformVideo>? = null;

        fun asZip(): ByteArray {
            return ByteArrayOutputStream().use { byteStream ->
                ZipOutputStream(byteStream).use { zipStream ->
                    zipStream.putNextEntry(ZipEntry("exportInfo"));
                    zipStream.write(Json.encodeToString(exportInfo).toByteArray());

                    if(settings != null) {
                        zipStream.putNextEntry(ZipEntry("settings"));
                        zipStream.write(settings.toByteArray());
                    }

                    zipStream.putNextEntry(ZipEntry("stores/"));
                    for(store in stores.mapValues { Json.encodeToString(it.value) }) {
                        zipStream.putNextEntry(ZipEntry("stores/${store.key}"));
                        zipStream.write(store.value.toByteArray());
                    }

                    zipStream.putNextEntry(ZipEntry("plugins"));
                    zipStream.write(Json.encodeToString(plugins).toByteArray());

                    zipStream.putNextEntry(ZipEntry("plugin_settings"));
                    zipStream.write(Json.encodeToString(pluginSettings).toByteArray());
                };
                return byteStream.toByteArray();
            }
        }

        companion object {
            fun fromZip(zipStream: ZipInputStream): ExportStructure {
                var entry: ZipEntry? = null

                var exportInfo: Map<String, String> = mapOf();
                var settings: String? = null;
                var stores: MutableMap<String, List<String>> = mutableMapOf();
                var plugins: Map<String, String> = mapOf();
                var pluginSettings: Map<String, Map<String, String?>> = mapOf();

                while (zipStream.nextEntry.also { entry = it } != null) {
                    if(entry!!.isDirectory)
                        continue;
                    try{
                        if(!entry!!.name.startsWith("stores/"))
                            when(entry!!.name) {
                                "exportInfo" -> exportInfo = Json.decodeFromString(String(zipStream.readBytes()));
                                "settings" -> settings = String(zipStream.readBytes());
                                "plugins" -> plugins = Json.decodeFromString(String(zipStream.readBytes()));
                                "plugin_settings" -> pluginSettings = Json.decodeFromString(String(zipStream.readBytes()));
                            }
                        else
                            stores[entry!!.name.substring("stores/".length)] = Json.decodeFromString(String(zipStream.readBytes()));
                    }
                    catch(ex: Throwable) {
                        throw IllegalStateException("Failed to parse zip [${entry?.name}] due to ${ex.message}");
                    }
                }
                return ExportStructure(exportInfo, settings, stores, plugins, pluginSettings);
            }
        }
    }
}