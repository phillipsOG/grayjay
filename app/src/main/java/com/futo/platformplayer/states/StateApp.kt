package com.futo.platformplayer.states

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.futo.platformplayer.*
import com.futo.platformplayer.R
import com.futo.platformplayer.activities.CaptchaActivity
import com.futo.platformplayer.activities.IWithResultLauncher
import com.futo.platformplayer.activities.MainActivity
import com.futo.platformplayer.api.media.Serializer
import com.futo.platformplayer.api.media.platforms.js.JSClient
import com.futo.platformplayer.api.media.platforms.js.internal.JSHttpClient
import com.futo.platformplayer.background.BackgroundWorker
import com.futo.platformplayer.casting.StateCasting
import com.futo.platformplayer.constructs.Event0
import com.futo.platformplayer.engine.exceptions.ScriptCaptchaRequiredException
import com.futo.platformplayer.fragment.mainactivity.main.HomeFragment
import com.futo.platformplayer.fragment.mainactivity.main.SourceDetailFragment
import com.futo.platformplayer.logging.AndroidLogConsumer
import com.futo.platformplayer.logging.FileLogConsumer
import com.futo.platformplayer.logging.LogLevel
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.receivers.AudioNoisyReceiver
import com.futo.platformplayer.services.DownloadService
import com.futo.platformplayer.stores.FragmentedStorage
import com.futo.platformplayer.stores.v2.ManagedStore
import com.stripe.android.core.utils.encodeToJson
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.OffsetDateTime
import java.util.*
import java.util.concurrent.TimeUnit

/***
 * This class contains global context for unconventional cases where obtaining context is hard.
 * This context is only alive while MainActivity is active
 * Ideally StateApp.withContext is used to only run code when it is available or throw
 */
class StateApp {
    val isMainActive: Boolean get() = contextOrNull != null && contextOrNull is MainActivity; //if context is MainActivity, it means its active

    /*
    private val externalRootDirectory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Grayjay");

    fun getExternalRootDirectory(): File? {
        if(!externalRootDirectory.exists()) {
            val result = externalRootDirectory.mkdirs();
            if(!result)
                return null;
            return externalRootDirectory;
        }
        else
            return externalRootDirectory;
    }*/

    fun getExternalGeneralDirectory(context: Context): DocumentFile? {
        val generalUri = Settings.instance.storage.getStorageGeneralUri();
        if(isValidStorageUri(context, generalUri))
            return DocumentFile.fromTreeUri(context, generalUri!!);
        return null;
    }
    fun changeExternalGeneralDirectory(context: IWithResultLauncher, onChanged: ((DocumentFile?)->Unit)? = null) {
        if(context is Context)
            requestDirectoryAccess(context, "General Files", "This directory is used to save auto-backups and other persistent files.", null) {
                if(it != null)
                    context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION.or(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                if(it != null && isValidStorageUri(context, it)) {
                    Logger.i(TAG, "Changed external general directory: ${it}");
                    Settings.instance.storage.storage_general = it.toString();
                    Settings.instance.save();

                    onChanged?.invoke(getExternalGeneralDirectory(context));
                }
                else
                    scopeOrNull?.launch(Dispatchers.Main) {
                        UIDialogs.toast("Failed to gain access to\n [${it?.lastPathSegment}]");
                    };
            };
    }
    fun getExternalDownloadDirectory(context: Context): DocumentFile? {
        val downloadUri = Settings.instance.storage.storage_download?.let { Uri.parse(it) };
        if(isValidStorageUri(context, downloadUri))
            return DocumentFile.fromTreeUri(context, downloadUri!!);
        return null;
    }
    fun changeExternalDownloadDirectory(context: IWithResultLauncher, onChanged: ((DocumentFile?)->Unit)? = null) {

        scopeOrNull?.launch(Dispatchers.Main) {
            UIDialogs.toast("External download directory not yet used by export (WIP)");
        };
        if(context is Context)
            requestDirectoryAccess(context, "Download Exports", "This directory is used to export downloads to for external usage.", null) {
                if(it != null)
                    context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_WRITE_URI_PERMISSION.or(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                if(it != null && isValidStorageUri(context, it)) {
                    Logger.i(TAG, "Changed external download directory: ${it}");
                    Settings.instance.storage.storage_general = it.toString();
                    Settings.instance.save();

                    onChanged?.invoke(getExternalDownloadDirectory(context));
                }
            };
    }
    fun isValidStorageUri(context: Context, uri: Uri?): Boolean {
        if(uri == null)
            return false;

        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission };
    }

    //Scope
    private var _scope: CoroutineScope? = null;
    val scopeOrNull: CoroutineScope? get() {
        return _scope;
    }
    val scope: CoroutineScope get() {
        val thisScope = scopeOrNull
            ?: throw IllegalStateException("Attempted to use a global lifetime scope while MainActivity is no longer available");
        return thisScope;
    }
    val scopeGetter: ()->CoroutineScope get() {
        return {scope};
    }

    var displayMetrics: DisplayMetrics? = null;

    //Context
    private var _context: Context? = null;
    val contextOrNull: Context? get() {
        return _context;
    }
    val context: Context get() {
        val thisContext = contextOrNull
            ?: throw IllegalStateException("Attempted to use a global context while MainActivity is no longer available");
        return thisContext;
    }

    //Files
    private var _tempDirectory: File? = null;


    //AutoRotate
    var systemAutoRotate: Boolean = false;

    //Network
    private var _lastMeteredState: Boolean = false;
    private var _connectivityManager: ConnectivityManager? = null;
    private var _lastNetworkState: NetworkState = NetworkState.UNKNOWN;

    //Logging
    private var _fileLogConsumer: FileLogConsumer? = null;

    //Receivers
    private var _receiverBecomingNoisy: AudioNoisyReceiver? = null;

    val onConnectionAvailable = Event0();
    val preventPictureInPicture = Event0();

    fun getTempDirectory(): File {
        return _tempDirectory!!;
    }
    fun getTempFile(extension: String? = null): File {
        val name = UUID.randomUUID().toString() +
                if(extension != null)
                    ".${extension}"
                else
                    "";

        return File(_tempDirectory, name);
    }

    fun getCurrentSystemAutoRotate(): Boolean {
        _context?.let {
            systemAutoRotate = android.provider.Settings.System.getInt(
                it.contentResolver,
                android.provider.Settings.System.ACCELEROMETER_ROTATION, 0
            ) == 1;
        };
        return systemAutoRotate;
    }


    fun isCurrentMetered(): Boolean {
        ensureConnectivityManager();
        return _connectivityManager?.isActiveNetworkMetered ?: throw IllegalStateException("Connectivity manager not available");
    }
    fun isNetworkState(vararg states: NetworkState): Boolean {
        return states.contains(getCurrentNetworkState());
    }
    fun getCurrentNetworkState(): NetworkState {
        var state = NetworkState.DISCONNECTED;

        ensureConnectivityManager();
        _connectivityManager?.activeNetwork?.let {
            val networkCapabilities = _connectivityManager?.getNetworkCapabilities(it) ?: throw IllegalStateException("Connectivity manager could not be found");

            val connected = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);

            if(connected && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                state = NetworkState.ETHERNET;
                return@let;
            }
            if(connected && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                state = NetworkState.WIFI;
                return@let;
            }
            if(connected && networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                state = NetworkState.CELLULAR;
                return@let;
            }
        }
        return state;
    }

    fun requestDirectoryAccess(activity: IWithResultLauncher, name: String, purpose: String? = null, path: Uri?, handle: (Uri?)->Unit)
    {
        if(activity is Context)
        {
            UIDialogs.showDialog(activity, R.drawable.ic_security, "Directory required for\n${name}", "Please select a directory for ${name}.\n${purpose}".trim(), null, 0,
                UIDialogs.Action("Cancel", {}),
                UIDialogs.Action("Ok", {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    if(path != null)
                        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, path);
                    intent.flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        .or(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .or(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                        .or(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);

                    activity.launchForResult(intent, 99) {
                        if(it.resultCode == Activity.RESULT_OK) {
                            handle(it.data?.data);
                        }
                        else
                            UIDialogs.showDialogOk(context, R.drawable.ic_security_pred, "No access granted");
                    };
                }, UIDialogs.ActionStyle.PRIMARY));
        }
    }

    //Lifecycle
    fun setGlobalContext(context: Context, coroutineScope: CoroutineScope? = null) {
        _context = context;
        _scope = coroutineScope

        //System checks
        systemAutoRotate = getCurrentSystemAutoRotate();
    }

    fun initializeFiles(force: Boolean = false) {
        if(force || !FragmentedStorage.isInitialized) {
            FragmentedStorage.initialize(context.filesDir);
            _tempDirectory = File(context.filesDir, "temp");
            if (_tempDirectory?.exists() == true) {
                Logger.i(TAG, "Deleting ${_tempDirectory?.listFiles()?.size} temp files");
                _tempDirectory?.deleteRecursively();
            }
            _tempDirectory?.mkdirs();
        }
    }

    /***
     * This method starts a background context, should only be used under the assumption that your app is not active (eg. Scheduled background worker)
     */
    suspend fun startBackground(context: Context, withFiles: Boolean, withPlugins: Boolean, backgroundWorker: suspend () -> Unit) {
        withContext(Dispatchers.IO) {
            backgroundStarting(context, this, withFiles, withPlugins);
            try {
                backgroundWorker();
            }
            catch (ex: Throwable) {
                Logger.e(TAG, "Background work failed: ${ex.message}", ex);
                throw ex;
            }
            finally {
                backgroundStopping(context);
            }
        }
    }
    suspend fun backgroundStarting(context: Context, scope: CoroutineScope, withFiles: Boolean, withPlugins: Boolean) {
        if(contextOrNull == null) {
            Logger.i(TAG, "BACKGROUND STATE: Starting");
            if(!Logger.hasConsumers && BuildConfig.DEBUG) {
                Logger.i(TAG, "BACKGROUND STATE: Initialize logger");
                Logger.setLogConsumers(listOf(AndroidLogConsumer()));
            }

            Logger.i(TAG, "BACKGROUND STATE: Initialize context");
            setGlobalContext(context, scope);

            if(withFiles) {
                Logger.i(TAG, "BACKGROUND STATE: Initialize files");
                initializeFiles();
            }

            if (withPlugins) {
                Logger.i(TAG, "BACKGROUND STATE: Initialize plugins");
                StatePlatform.instance.updateAvailableClients(context, true);
            }
        }
    }
    fun backgroundStopping(context: Context) {
        if(contextOrNull == context || contextOrNull == null) {
            Logger.i(TAG, "STOPPING BACKGROUND STATE");
            StatePlatform.instance.disableAllClients();
            dispose();
        }
    }

    fun mainAppStarting(context: Context) {
        initializeFiles(true);

        val logFile = File(context.filesDir, "log.txt");
        if (Settings.instance.logging.logLevel > LogLevel.NONE.value) {
            val fileLogConsumer = FileLogConsumer(logFile, LogLevel.fromInt(Settings.instance.logging.logLevel), false);
            Logger.setLogConsumers(listOf(
                AndroidLogConsumer(),
                fileLogConsumer
            ));

            _fileLogConsumer = fileLogConsumer;
        } else if (BuildConfig.DEBUG) {
            if (logFile.exists()) {
                logFile.delete();
            }

            Logger.setLogConsumers(listOf(AndroidLogConsumer()));
        }

        StatePayment.instance.initialize();
        StatePolycentric.instance.load(context);
        StateSaved.instance.load();

        displayMetrics = context.resources.displayMetrics;
        ensureConnectivityManager(context);

        if (!BuildConfig.DEBUG) {
            StateTelemetry.instance.initialize();
            StateTelemetry.instance.upload();
        }

        Logger.onLogSubmitted.subscribe {
            scopeOrNull?.launch(Dispatchers.Main) {
                try {
                    if (it != null) {
                        UIDialogs.toast("Uploaded " + (it ?: "null"), true);
                    } else {
                        UIDialogs.toast("Failed to upload");
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "Failed to show toast", e)
                }
            }
        }
    }
    fun mainAppStarted(context: Context) {
        Logger.i(TAG, "App started");

        StateAnnouncement.instance.registerAnnouncement("fa4647d3-36fa-4c8c-832d-85b00fc72dca", "Disclaimer", "This is an early alpha build of the application, expect bugs and unfinished features.", AnnouncementType.DELETABLE, OffsetDateTime.now())

        if(SettingsDev.instance.developerMode && SettingsDev.instance.devServerSettings.devServerOnBoot)
            StateDeveloper.instance.runServer();

        if(StateSubscriptions.instance.shouldMigrate())
            StateSubscriptions.instance.tryMigrateIfNecessary();

        if(Settings.instance.downloads.shouldDownload()) {
            StateDownloads.instance.checkForOutdatedPlaylists();

            StateDownloads.instance.getDownloadPlaylists();
            if (!StateDownloads.instance.getDownloading().isEmpty())
                DownloadService.getOrCreateService(context);
        }

        StateDownloads.instance.checkForExportTodos();

        val autoUpdateEnabled = Settings.instance.autoUpdate.isAutoUpdateEnabled();
        val shouldDownload = Settings.instance.autoUpdate.shouldDownload();
        val backgroundDownload = Settings.instance.autoUpdate.backgroundDownload == 1;
        when {
            //Background download
            autoUpdateEnabled && shouldDownload && backgroundDownload -> {
                StateUpdate.instance.setShouldBackgroundUpdate(true);
            }

            autoUpdateEnabled && !shouldDownload && backgroundDownload -> {
                Logger.i(TAG, "Auto update skipped due to wrong network state");
            }

            //Foreground download
            autoUpdateEnabled -> {
                StateUpdate.instance.checkForUpdates(context, false);
            }

            else -> {
                Logger.i(TAG, "Auto update disabled");
            }
        }

        _receiverBecomingNoisy?.let {
            _receiverBecomingNoisy = null;
            context.unregisterReceiver(it);
        }
        _receiverBecomingNoisy = AudioNoisyReceiver();
        context.registerReceiver(_receiverBecomingNoisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY));

        //Migration
        migrateStores(context, listOf(
            StateSubscriptions.instance.toMigrateCheck(),
            StatePlaylists.instance.toMigrateCheck()
        ).flatten(), 0);

        if(Settings.instance.subscriptions.fetchOnAppBoot) {
            scope.launch(Dispatchers.IO) {
                val subRequestCounts = StateSubscriptions.instance.getSubscriptionRequestCount();
                val reqCountStr = subRequestCounts.map { "    ${it.key.config.name}: ${it.value}/${it.key.config.subscriptionRateLimit}" }.joinToString("\n");
                val isRateLimitReached = !subRequestCounts.any { clientCount -> clientCount.key.config.subscriptionRateLimit?.let { rateLimit -> clientCount.value > rateLimit } == true };
                if (isRateLimitReached) {
                    Logger.w(TAG, "Subscriptions request on boot, request counts:\n${reqCountStr}");
                    delay(5000);
                    StateSubscriptions.instance.updateSubscriptionFeed(scope, false);
                }
                else
                    Logger.w(TAG, "Too many subscription requests required:\n${reqCountStr}");
            }
        }

        val interval = Settings.instance.subscriptions.getSubscriptionsBackgroundIntervalMinutes();
        scheduleBackgroundWork(context, interval != 0, interval);

        if(!Settings.instance.backup.didAskAutoBackup && !Settings.instance.backup.shouldAutomaticBackup()) {
            StateAnnouncement.instance.registerAnnouncement("backup", "Set Automatic Backup", "Configure daily backups of your data to restore in case of catastrophic failure.", AnnouncementType.SESSION, null, null, "Configure", {
                if(context is IWithResultLauncher && !Settings.instance.storage.isStorageMainValid(context)) {
                    UIDialogs.toast("Missing general directory");
                    changeExternalGeneralDirectory(context) {
                        UIDialogs.showAutomaticBackupDialog(context);
                        StateAnnouncement.instance.deleteAnnouncement("backup");
                    };
                }
                else {
                    UIDialogs.showAutomaticBackupDialog(context);
                    StateAnnouncement.instance.deleteAnnouncement("backup");
                }
            }, "No Backup", {
                Settings.instance.backup.didAskAutoBackup = true;
                Settings.instance.save();
            });
        }
        else if(Settings.instance.backup.didAskAutoBackup && Settings.instance.backup.shouldAutomaticBackup() && !Settings.instance.storage.isStorageMainValid(context)) {
            if(context is IWithResultLauncher) {
                Logger.i(TAG, "Backup set without general directory, please select general external directory");
                changeExternalGeneralDirectory(context) {
                    Logger.i(TAG, "Directory set, Auto-backup should resume to this location");
                };
            }
        }

        instance.scopeOrNull?.launch(Dispatchers.IO) {
            try {
                StateAnnouncement.instance.loadAnnouncements();
            } catch (e: Throwable) {
                Logger.e(TAG, "Failed to load announcements.", e)
            }
        }

        if (BuildConfig.IS_PLAYSTORE_BUILD) {
            StateAnnouncement.instance.registerAnnouncement(
                "playstore-version",
                "Playstore version",
                "This version is the playstore version of the app. Your experience will be more limited.",
                AnnouncementType.SESSION_RECURRING
            );
        }

        StateAnnouncement.instance.registerDidYouKnow();

    }
    fun mainAppStartedWithExternalFiles(context: Context) {
        if(!Settings.instance.didFirstStart) {
            if(StateBackup.hasAutomaticBackup()) {
                UIDialogs.showAutomaticRestoreDialog(context, if(context is LifecycleOwner) context.lifecycleScope else scope);
            }


            Settings.instance.didFirstStart = true;
            Settings.instance.save();
        }
        if(Settings.instance.backup.shouldAutomaticBackup()) {
            try {
                StateBackup.startAutomaticBackup();
            }
            catch(ex: Throwable) {
                Logger.e("StateApp", "Automatic backup failed", ex);
                UIDialogs.toast(context, "Automatic backup failed due to:\n" + ex.message);
            }
        }
        else
            Logger.i("StateApp", "No AutoBackup configured");
    }


    fun scheduleBackgroundWork(context: Context, active: Boolean = true, intervalMinutes: Int = 60 * 12) {
        val wm = WorkManager.getInstance(context);

        if(active) {
            if(BuildConfig.DEBUG)
                UIDialogs.toast(context, "Scheduling background every ${intervalMinutes} minutes");

            val req = PeriodicWorkRequest.Builder(BackgroundWorker::class.java, intervalMinutes.toLong(), TimeUnit.MINUTES, 5, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.UNMETERED)
                    .build())
                .build();
            wm.enqueueUniquePeriodicWork("backgroundSubscriptions", ExistingPeriodicWorkPolicy.UPDATE, req);
        }
        else
            wm.cancelAllWork();
    }


    private fun migrateStores(context: Context, managedStores: List<ManagedStore<*>>, index: Int) {
        if(managedStores.size <= index)
            return;
        val store = managedStores[index];
        if(store.hasMissingReconstructions())
            UIDialogs.showMigrateDialog(context, store) {
                migrateStores(context, managedStores, index + 1);
            };
        else
            migrateStores(context, managedStores, index + 1);
    }

    fun mainAppDestroyed(context: Context) {
        Logger.i(TAG, "App ended");
        _receiverBecomingNoisy?.let {
            _receiverBecomingNoisy = null;
            context.unregisterReceiver(it);
        }

        Logger.i(TAG, "Unregistered network callback on connectivityManager.")
        _connectivityManager?.unregisterNetworkCallback(_connectivityEvents);

        StatePlayer.instance.closeMediaSession();
        StateCasting.instance.stop();
        StatePlayer.dispose();
        Companion.dispose();
        _fileLogConsumer?.close();
    }

    fun dispose(){
        _context = null;
        _scope = null;
    }

    private val _connectivityEvents = object : ConnectivityManager.NetworkCallback() {
        override fun onUnavailable() {
            super.onUnavailable();
            Logger.i(TAG, "_connectivityEvents onUnavailable");

            updateNetworkState();
        }

        override fun onLost(network: Network) {
            super.onLost(network);
            Logger.i(TAG, "_connectivityEvents onLost");

            updateNetworkState();
        }

        override fun onAvailable(network: Network) {
            super.onAvailable(network);
            Logger.i(TAG, "_connectivityEvents onAvailable");

            updateNetworkState();

            try {
                if (_lastNetworkState != NetworkState.DISCONNECTED) {
                    scopeOrNull?.launch(Dispatchers.Main) {
                        try {
                            onConnectionAvailable.emit();
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Failed to emit onConnectionAvailable", e)
                        }
                    };
                }
            } catch(ex: Throwable) {
                Logger.w(TAG, "Failed to handle connection available event", ex);
            }
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);

            updateNetworkState();

            try {
                if(FragmentedStorage.isInitialized && Settings.instance.downloads.shouldDownload())
                    StateDownloads.instance.checkForDownloadsTodos();

                val autoUpdateEnabled = Settings.instance.autoUpdate.isAutoUpdateEnabled();
                val shouldDownload = Settings.instance.autoUpdate.shouldDownload();
                val backgroundDownload = Settings.instance.autoUpdate.backgroundDownload == 1;
                if (autoUpdateEnabled && shouldDownload && backgroundDownload) {
                    StateUpdate.instance.setShouldBackgroundUpdate(true);
                } else {
                    StateUpdate.instance.setShouldBackgroundUpdate(false);
                }
            } catch(ex: Throwable) {
                Logger.w(TAG, "Failed to handle capabilities changed event", ex);
            }
        }

        private fun updateNetworkState() {
            try {
                val beforeNetworkState = _lastNetworkState;
                val beforeMeteredState = _lastMeteredState;
                _lastNetworkState = getCurrentNetworkState();
                _lastMeteredState = isCurrentMetered();
                if(beforeNetworkState != _lastNetworkState || beforeMeteredState != _lastMeteredState)
                    Logger.i(TAG, "Network capabilities changed (State: ${_lastNetworkState}, Metered: ${_lastMeteredState})");
            } catch(ex: Throwable) {
                Logger.w(TAG, "Failed to update network state", ex);
            }
        }
    };
    private fun ensureConnectivityManager(context: Context? = null) {
        if(_connectivityManager == null) {
            _connectivityManager =
                (context ?: contextOrNull)?.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager?
                    ?: throw IllegalStateException("Connectivity manager could not be found");

            val netReq = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build();
            _connectivityManager!!.registerNetworkCallback(netReq, _connectivityEvents);
        }
    }

    private var hasCaptchaDialog = false;
    fun handleCaptchaException(client: JSClient, exception: ScriptCaptchaRequiredException) {
        Logger.w(HomeFragment.TAG, "[${client.name}] Plugin captcha required.", exception);

        scopeOrNull?.launch(Dispatchers.Main) {
            if(hasCaptchaDialog)
                return@launch;
            hasCaptchaDialog = true;
            UIDialogs.showConfirmationDialog(context, "Captcha required\nPlugin [${client.config.name}]", {
                CaptchaActivity.showCaptcha(context, client.config, exception.url, exception.body) {
                    hasCaptchaDialog = false;
                    StatePlugins.instance.setPluginCaptcha(client.config.id, it);
                    scopeOrNull?.launch(Dispatchers.IO) {
                        try {
                            StatePlatform.instance.reloadClient(context, client.config.id);
                        } catch (e: Throwable) {
                            Logger.e(SourceDetailFragment.TAG, "Failed to reload client.", e)
                            return@launch;
                        }
                    }
                }
            }, {
                hasCaptchaDialog = false;
            })
        }
    }

    companion object {
        private val TAG = "StateApp";
        @SuppressLint("StaticFieldLeak") //This is only alive while MainActivity is alive
        private var _instance : StateApp? = null;
        val instance : StateApp
            get(){
            if(_instance == null)
                _instance = StateApp();
            return _instance!!;
        };

        fun dispose(){
            val instance = _instance;
            _instance = null;
            instance?.dispose();
            Logger.i(TAG, "StateApp has been disposed");
        }

        fun withContext(handle: (Context)->Unit) {
            val context = _instance?.contextOrNull;
            if(context != null)
                handle(context);
        }
        fun withContext(throwIfNotAvailable: Boolean, handle: (Context)->Unit) {
            if(!throwIfNotAvailable)
                withContext(handle);
            val context = _instance?.contextOrNull;
            if(context != null)
                handle(context);
            else if(throwIfNotAvailable)
                throw IllegalStateException("Attempted to use a global context while MainActivity is no longer available");
        }
    }


    enum class NetworkState {
        UNKNOWN,
        DISCONNECTED,
        CELLULAR,
        WIFI,
        ETHERNET
    }
}