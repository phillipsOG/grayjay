package com.futo.platformplayer.activities

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.futo.platformplayer.*
import com.futo.platformplayer.api.media.PlatformID
import com.futo.platformplayer.api.media.models.channels.SerializedChannel
import com.futo.platformplayer.casting.StateCasting
import com.futo.platformplayer.constructs.Event1
import com.futo.platformplayer.constructs.Event3
import com.futo.platformplayer.fragment.mainactivity.bottombar.MenuBottomBarFragment
import com.futo.platformplayer.fragment.mainactivity.main.*
import com.futo.platformplayer.fragment.mainactivity.topbar.AddTopBarFragment
import com.futo.platformplayer.fragment.mainactivity.topbar.GeneralTopBarFragment
import com.futo.platformplayer.fragment.mainactivity.topbar.ImportTopBarFragment
import com.futo.platformplayer.fragment.mainactivity.topbar.NavigationTopBarFragment
import com.futo.platformplayer.fragment.mainactivity.topbar.SearchTopBarFragment
import com.futo.platformplayer.listeners.OrientationManager
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.models.UrlVideoWithTime
import com.futo.platformplayer.states.*
import com.futo.platformplayer.stores.FragmentedStorage
import com.futo.platformplayer.stores.SubscriptionStorage
import com.futo.platformplayer.stores.v2.ManagedStore
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.InvocationTargetException
import java.util.*

class MainActivity : AppCompatActivity, IWithResultLauncher {

    //TODO: Move to dimensions
    private val HEIGHT_MENU_DP = 48f;
    private val HEIGHT_VIDEO_MINIMIZED_DP = 60f;

    //Containers
    lateinit var rootView : MotionLayout;

    private lateinit var _overlayContainer: FrameLayout;

    //Segment Containers
    private lateinit var _fragContainerTopBar: FragmentContainerView;
    private lateinit var _fragContainerMain: FragmentContainerView;
    private lateinit var _fragContainerBotBar: FragmentContainerView;
    private lateinit var _fragContainerVideoDetail: FragmentContainerView;
    private lateinit var _fragContainerOverlay: FrameLayout;

    //Frags TopBar
    lateinit var _fragTopBarGeneral: GeneralTopBarFragment;
    lateinit var _fragTopBarSearch: SearchTopBarFragment;
    lateinit var _fragTopBarNavigation: NavigationTopBarFragment;
    lateinit var _fragTopBarImport: ImportTopBarFragment;
    lateinit var _fragTopBarAdd: AddTopBarFragment;

    //Frags BotBar
    lateinit var _fragBotBarMenu: MenuBottomBarFragment;

    //Frags Main
    lateinit var _fragMainHome: HomeFragment;
    lateinit var _fragPostDetail: PostDetailFragment;
    lateinit var _fragMainVideoSearchResults: ContentSearchResultsFragment;
    lateinit var _fragMainCreatorSearchResults: CreatorSearchResultsFragment;
    lateinit var _fragMainPlaylistSearchResults: PlaylistSearchResultsFragment;
    lateinit var _fragMainSuggestions: SuggestionsFragment;
    lateinit var _fragMainSubscriptions: CreatorsFragment;
    lateinit var _fragMainSubscriptionsFeed: SubscriptionsFeedFragment;
    lateinit var _fragMainChannel: ChannelFragment;
    lateinit var _fragMainSources: SourcesFragment;
    lateinit var _fragMainPlaylists: PlaylistsFragment;
    lateinit var _fragMainPlaylist: PlaylistFragment;
    lateinit var _fragWatchlist: WatchLaterFragment;
    lateinit var _fragHistory: HistoryFragment;
    lateinit var _fragSourceDetail: SourceDetailFragment;
    lateinit var _fragDownloads: DownloadsFragment;
    lateinit var _fragImportSubscriptions: ImportSubscriptionsFragment;
    lateinit var _fragImportPlaylists: ImportPlaylistsFragment;
    lateinit var _fragBuy: BuyFragment;

    lateinit var _fragBrowser: BrowserFragment;

    //Frags Overlay
    lateinit var _fragVideoDetail: VideoDetailFragment;

    //State
    private val _queue : Queue<Pair<MainFragment, Any?>> = LinkedList();
    lateinit var fragCurrent : MainFragment private set;
    private var _parameterCurrent: Any? = null;

    var fragBeforeOverlay : MainFragment? = null; private set;

    val onNavigated = Event1<MainFragment>();

    private lateinit var _orientationManager: OrientationManager;
    var orientation: OrientationManager.Orientation = OrientationManager.Orientation.PORTRAIT
        private set;
    private var _isVisible = true;
    private var _wasStopped = false;

    constructor() : super() {
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            val writer = StringWriter();

            var excp = throwable;
            Logger.e("Application", "Uncaught", excp);

            //Resolve invocation chains
            while(excp is InvocationTargetException || excp is java.lang.RuntimeException) {
                val before = excp;

                if(excp is InvocationTargetException)
                    excp = excp.targetException ?: excp.cause ?: excp;
                else if(excp is java.lang.RuntimeException)
                    excp = excp.cause ?: excp;

                if(excp == before)
                    break;
            }
            writer.write((excp.message ?: "Empty error") + "\n\n");
            excp.printStackTrace(PrintWriter(writer));
            val message = writer.toString();
            Logger.e(TAG, message, excp);

            val exIntent = Intent(this, ExceptionActivity::class.java);
            exIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
            exIntent.putExtra(ExceptionActivity.EXTRA_STACK, message);
            startActivity(exIntent);

            Runtime.getRuntime().exit(0);
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        StateApp.instance.setGlobalContext(this, lifecycleScope);
        StateApp.instance.mainAppStarting(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setNavigationBarColorAndIcons();

        runBlocking {
            StatePlatform.instance.updateAvailableClients(this@MainActivity);
        }

        //Preload common files to memory
        FragmentedStorage.get<SubscriptionStorage>();
        FragmentedStorage.get<Settings>();

        rootView = findViewById(R.id.rootView);
        _fragContainerTopBar = findViewById(R.id.fragment_top_bar);
        _fragContainerMain = findViewById(R.id.fragment_main);
        _fragContainerBotBar = findViewById(R.id.fragment_bottom_bar);
        _fragContainerVideoDetail = findViewById(R.id.fragment_overlay);
        _fragContainerOverlay = findViewById(R.id.fragment_overlay_container);
        _overlayContainer = findViewById(R.id.overlay_container);
        //_overlayContainer.visibility = View.GONE;

        //Initialize fragments

        //TopBars
        _fragTopBarGeneral = GeneralTopBarFragment.newInstance();
        _fragTopBarSearch = SearchTopBarFragment.newInstance();
        _fragTopBarNavigation = NavigationTopBarFragment.newInstance();
        _fragTopBarImport = ImportTopBarFragment.newInstance();
        _fragTopBarAdd = AddTopBarFragment.newInstance();

        //BotBars
        _fragBotBarMenu = MenuBottomBarFragment.newInstance();

        //Main
        _fragMainHome = HomeFragment.newInstance();
        _fragMainSuggestions = SuggestionsFragment.newInstance();
        _fragMainVideoSearchResults = ContentSearchResultsFragment.newInstance();
        _fragMainCreatorSearchResults = CreatorSearchResultsFragment.newInstance();
        _fragMainPlaylistSearchResults = PlaylistSearchResultsFragment.newInstance();
        _fragMainSubscriptions = CreatorsFragment.newInstance();
        _fragMainChannel = ChannelFragment.newInstance();
        _fragMainSubscriptionsFeed = SubscriptionsFeedFragment.newInstance();
        _fragMainSources = SourcesFragment.newInstance();
        _fragMainPlaylists = PlaylistsFragment.newInstance();
        _fragMainPlaylist = PlaylistFragment.newInstance();
        _fragPostDetail = PostDetailFragment.newInstance();
        _fragWatchlist = WatchLaterFragment.newInstance();
        _fragHistory = HistoryFragment.newInstance();
        _fragSourceDetail = SourceDetailFragment.newInstance();
        _fragDownloads = DownloadsFragment();
        _fragImportSubscriptions = ImportSubscriptionsFragment.newInstance();
        _fragImportPlaylists = ImportPlaylistsFragment.newInstance();
        _fragBuy = BuyFragment.newInstance();

        _fragBrowser = BrowserFragment.newInstance();

        //Overlays
        _fragVideoDetail = VideoDetailFragment.newInstance();
        //Overlay Init
        _fragVideoDetail.onMinimize.subscribe { };
        _fragVideoDetail.onShownEvent.subscribe {
            _fragMainHome.setPreviewsEnabled(false);
            _fragMainVideoSearchResults.setPreviewsEnabled(false);
            _fragMainSubscriptionsFeed.setPreviewsEnabled(false);
        };


        _fragVideoDetail.onMinimize.subscribe {
            updateSegmentPaddings();
        };
        _fragVideoDetail.onTransitioning.subscribe {
            if(it || _fragVideoDetail.state != VideoDetailFragment.State.MINIMIZED)
                _fragContainerOverlay.elevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 15f, resources.displayMetrics);
            else
                _fragContainerOverlay.elevation = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 5f, resources.displayMetrics);
        }

        _fragVideoDetail.onCloseEvent.subscribe {
            _fragMainHome.setPreviewsEnabled(true);
            _fragMainVideoSearchResults.setPreviewsEnabled(true);
            _fragMainSubscriptionsFeed.setPreviewsEnabled(true);
            _fragContainerVideoDetail.visibility = View.INVISIBLE;
            updateSegmentPaddings();
        };

        StatePlayer.instance.also {
            it.onQueueChanged.subscribe { shouldSwapCurrentItem ->
                if (!shouldSwapCurrentItem) {
                    return@subscribe;
                }

                if(_fragVideoDetail.state == VideoDetailFragment.State.CLOSED) {
                    if (fragCurrent !is VideoDetailFragment) {
                        val toPlay = StatePlayer.instance.getCurrentQueueItem();
                        navigate(_fragVideoDetail, toPlay);

                        if (!StatePlayer.instance.queueFocused)
                            _fragVideoDetail.minimizeVideoDetail();
                    }
                } else {
                    val toPlay = StatePlayer.instance.getCurrentQueueItem() ?: return@subscribe;
                    Logger.i(TAG, "Queue changed _fragVideoDetail.currentUrl=${_fragVideoDetail.currentUrl} toPlay.url=${toPlay.url}")
                    if (_fragVideoDetail.currentUrl == null || _fragVideoDetail.currentUrl != toPlay.url) {
                        navigate(_fragVideoDetail, toPlay);
                    }
                }
            };
        }

        onNavigated.subscribe {
            updateSegmentPaddings();
        }


        //Set top bars
        _fragMainHome.topBar = _fragTopBarGeneral;
        _fragMainSubscriptions.topBar = _fragTopBarGeneral;
        _fragMainSuggestions.topBar = _fragTopBarSearch;
        _fragMainVideoSearchResults.topBar = _fragTopBarSearch;
        _fragMainCreatorSearchResults.topBar = _fragTopBarSearch;
        _fragMainPlaylistSearchResults.topBar = _fragTopBarSearch;
        _fragMainChannel.topBar = _fragTopBarNavigation;
        _fragMainSubscriptionsFeed.topBar = _fragTopBarGeneral;
        _fragMainSources.topBar = _fragTopBarAdd;
        _fragMainPlaylists.topBar = _fragTopBarGeneral;
        _fragMainPlaylist.topBar = _fragTopBarNavigation;
        _fragPostDetail.topBar = _fragTopBarNavigation;
        _fragWatchlist.topBar = _fragTopBarNavigation;
        _fragHistory.topBar = _fragTopBarNavigation;
        _fragSourceDetail.topBar = _fragTopBarNavigation;
        _fragDownloads.topBar = _fragTopBarGeneral;
        _fragImportSubscriptions.topBar = _fragTopBarImport;
        _fragImportPlaylists.topBar = _fragTopBarImport;

        _fragBrowser.topBar = _fragTopBarNavigation;

        fragCurrent = _fragMainHome;

        val defaultTab = Settings.instance.tabs.mapNotNull {
            val buttonDefinition = MenuBottomBarFragment.buttonDefinitions.firstOrNull { bd -> it.id == bd.id };
            if (buttonDefinition == null) {
                return@mapNotNull null;
            } else {
                return@mapNotNull Pair(it, buttonDefinition);
            }
        }.first { it.first.enabled }.second;

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_top_bar, _fragTopBarGeneral)
            .replace(R.id.fragment_main, _fragMainHome)
            .replace(R.id.fragment_bottom_bar, _fragBotBarMenu)
            .replace(R.id.fragment_overlay, _fragVideoDetail)
            .commitNow();

        defaultTab.action(_fragBotBarMenu);

        _orientationManager = OrientationManager(this);
        _orientationManager.onOrientationChanged.subscribe {
            orientation = it;
            Logger.i(TAG, "Orientation changed (Found ${it})");
            fragCurrent.onOrientationChanged(it);
            if(_fragVideoDetail.state == VideoDetailFragment.State.MAXIMIZED)
                _fragVideoDetail.onOrientationChanged(it);
        };
        _orientationManager.enable();

        StateSubscriptions.instance;

        fragCurrent.onShown(null, false);

        //Other stuff
        rootView.progress = 0f;

        handleIntent(intent);

        if (Settings.instance.casting.enabled) {
            StateCasting.instance.start(this);
        }

        StatePlatform.instance.onDevSourceChanged.subscribe {
            Logger.i(TAG, "onDevSourceChanged")

            lifecycleScope.launch(Dispatchers.Main) {
                try {
                    if (!_isVisible) {
                        val bringUpIntent = Intent(this@MainActivity, MainActivity::class.java);
                        bringUpIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                        bringUpIntent.action = "TAB";
                        bringUpIntent.putExtra("TAB", "Sources");
                        startActivity(bringUpIntent);
                    } else {
                        _fragVideoDetail.closeVideoDetails();
                        navigate(_fragMainSources);
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "Failed to make sources front.", e);
                }
            }
        };

        StateApp.instance.mainAppStarted(this);

        //if(ContextCompat.checkSelfPermission(this, Manifest.permission.MANAGE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
        //    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.MANAGE_EXTERNAL_STORAGE), 123);
        //else
        StateApp.instance.mainAppStartedWithExternalFiles(this);

        //startActivity(Intent(this, TestActivity::class.java));
    }


    /*
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode != 123)
            return;

        if(grantResults.size == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            StateApp.instance.mainAppStartedWithExternalFiles(this);
        else {
            UIDialogs.showDialog(this, R.drawable.ic_help, "File Permissions", "Grayjay requires file permissions for exporting downloads and automatic backups", null, 0,
                UIDialogs.Action("Cancel", {}),
                UIDialogs.Action("Configure", {
                    startActivity(Intent().apply {
                        action = android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION;
                        data = Uri.fromParts("package", packageName, null)
                    });
                }, UIDialogs.ActionStyle.PRIMARY));
        }
            UIDialogs.toast(this, "No external file permissions\nExporting and auto backups will not work");
    }*/

    override fun onResume() {
        super.onResume();
        Logger.v(TAG, "onResume")

        val curOrientation = _orientationManager.orientation;

        if(_fragVideoDetail.state == VideoDetailFragment.State.MAXIMIZED && _fragVideoDetail.lastOrientation != curOrientation) {
            Logger.i(TAG, "Orientation mismatch (Found ${curOrientation})");
            orientation = curOrientation;
            fragCurrent.onOrientationChanged(curOrientation);
            if(_fragVideoDetail.state == VideoDetailFragment.State.MAXIMIZED)
                _fragVideoDetail.onOrientationChanged(curOrientation);
        }

        _isVisible = true;
        val videoToOpen = StateSaved.instance.videoToOpen;

        if (_wasStopped) {
            _wasStopped = false;

            if (videoToOpen != null && _fragVideoDetail.state == VideoDetailFragment.State.CLOSED) {
                Logger.i(TAG, "onResume videoToOpen=$videoToOpen");
                if (StatePlatform.instance.hasEnabledVideoClient(videoToOpen.url)) {
                    navigate(_fragVideoDetail, UrlVideoWithTime(videoToOpen.url, videoToOpen.timeSeconds, false));
                    _fragVideoDetail.maximizeVideoDetail(true);
                }

                StateSaved.instance.setVideoToOpenNonBlocking(null);
            }
        }
    }

    override fun onPause() {
        super.onPause();
        Logger.v(TAG, "onPause")
        _isVisible = false;
    }

    override fun onStop() {
        super.onStop()
        Logger.v(TAG, "_wasStopped = true");
        _wasStopped = true;
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private fun handleIntent(intent: Intent?) {
        if(intent == null)
            return;
        Logger.i(TAG, "handleIntent started by " + intent.action);


        var targetData: String? = null;

        when(intent.action) {
            Intent.ACTION_SEND -> {
                targetData = intent.getStringExtra(Intent.EXTRA_STREAM) ?: intent.getStringExtra(Intent.EXTRA_TEXT);
                Logger.i(TAG, "Share Received: " + targetData);
            }
            Intent.ACTION_VIEW -> {
                targetData = intent.dataString

                if(!targetData.isNullOrEmpty()) {
                    Logger.i(TAG, "View Received: " + targetData);
                }
            }
            "TAB" -> {
                when(intent.getStringExtra("TAB")){
                    "Sources" -> {
                        runBlocking {
                            StatePlatform.instance.updateAvailableClients(this@MainActivity, true) //Ideally this is not needed..
                            navigate(_fragMainSources);
                        }
                    };
                }
            }
        }

        try {
            if (targetData != null) {
                when(intent.scheme) {
                    "grayjay" -> {
                        if(targetData.startsWith("grayjay://license/")) {
                            if(StatePayment.instance.setPaymentLicenseUrl(targetData))
                            {
                                UIDialogs.showDialogOk(this, R.drawable.ic_check, "Your license key has been set!\nAn app restart might be required.");

                                if(fragCurrent is BuyFragment)
                                    closeSegment(fragCurrent);
                            }
                            else
                                UIDialogs.toast("Invalid license format");

                        }
                        else if(targetData.startsWith("grayjay://plugin/")) {
                            val intent = Intent(this, AddSourceActivity::class.java).apply {
                                data = Uri.parse(targetData.substring("grayjay://plugin/".length));
                            };
                            startActivity(intent);
                        }
                    }
                    "content" -> {
                        if(!handleContent(targetData, intent.type)) {
                            UIDialogs.showSingleButtonDialog(
                                this,
                                R.drawable.ic_play,
                                "Unknown content format [${targetData}]",
                                "Ok",
                                { });
                        }
                    }
                    "file" -> {
                        if(!handleFile(targetData)) {
                            UIDialogs.showSingleButtonDialog(
                                this,
                                R.drawable.ic_play,
                                "Unknown file format [${targetData}]",
                                "Ok",
                                { });
                        }
                    }
                    "polycentric" -> {
                        if(!handlePolycentric(targetData)) {
                            UIDialogs.showSingleButtonDialog(
                                this,
                                R.drawable.ic_play,
                                "Unknown Polycentric format [${targetData}]",
                                "Ok",
                                { });
                        }
                    }
                    else -> {
                        if (!handleUrl(targetData)) {
                            UIDialogs.showSingleButtonDialog(
                                this,
                                R.drawable.ic_play,
                                "Unknown url format [${targetData}]",
                                "Ok",
                                { });
                        }
                    }
                }
            }
        }
        catch(ex: Throwable) {
            UIDialogs.showGeneralErrorDialog(this, "Failed to handle file", ex);
        }
    }

    fun handleUrl(url: String): Boolean {
        Logger.i(TAG, "handleUrl(url=$url)")

        if (StatePlatform.instance.hasEnabledVideoClient(url)) {
            navigate(_fragVideoDetail, url);
            _fragVideoDetail.maximizeVideoDetail(true);
            return true;
        } else if(StatePlatform.instance.hasEnabledChannelClient(url)) {
            navigate(_fragMainChannel, url);

            lifecycleScope.launch {
                delay(100);
                _fragVideoDetail.minimizeVideoDetail();
            };
            return true;
        }
        return false;
    }
    fun handleContent(file: String, mime: String? = null): Boolean {
        Logger.i(TAG, "handleContent(url=$file)");

        val data = readSharedContent(file);
        if(file.lowercase().endsWith(".json") || mime == "application/json") {
            var recon = String(data);
            if(!recon.trim().startsWith("["))
                return handleUnknownJson(file, recon);

            val reconLines = Json.decodeFromString<List<String>>(recon);
            recon = reconLines.joinToString("\n");
            Logger.i(TAG, "Opened shared playlist reconstruction\n${recon}");
            handleReconstruction(recon);
            return true;
        }
        else if(file.lowercase().endsWith(".zip") || mime == "application/zip") {
            StateBackup.importZipBytes(this, lifecycleScope, data);
            return true;
        }
        return false;
    }
    fun handleFile(file: String): Boolean {
        Logger.i(TAG, "handleFile(url=$file)");
        if(file.lowercase().endsWith(".json")) {
            val recon = String(readSharedFile(file));
            if(!recon.startsWith("["))
                return handleUnknownJson(file, recon);

            Logger.i(TAG, "Opened shared playlist reconstruction\n${recon}");
            handleReconstruction(recon);
            return true;
        }
        else if(file.lowercase().endsWith(".zip")) {
            StateBackup.importZipBytes(this, lifecycleScope, readSharedFile(file));
            return true;
        }
        return false;
    }
    fun handleReconstruction(recon: String) {
        val type = ManagedStore.getReconstructionIdentifier(recon);
        val store: ManagedStore<*> = when(type) {
            "Playlist" -> StatePlaylists.instance.playlistStore
            else -> {
                UIDialogs.toast("Unknown reconstruction type ${type}", false);
                return;
            };
        };

        val name = when(type) {
            "Playlist" -> recon.split("\n").filter { !it.startsWith(ManagedStore.RECONSTRUCTION_HEADER_OPERATOR) }.firstOrNull() ?: type;
            else -> type
        }


        if(!type.isNullOrEmpty()) {
            UIDialogs.showImportDialog(this, store, name, listOf(recon)) {

            }
        }
    }

    fun handleUnknownJson(name: String?, json: String): Boolean {

        val context = this;

        //TODO: Proper import selection
        try {
            val newPipeSubsParsed = JsonParser.parseString(json).asJsonObject;
            if (!newPipeSubsParsed.has("subscriptions") || !newPipeSubsParsed["subscriptions"].isJsonArray)
                return false;//throw IllegalArgumentException("Invalid NewPipe json structure found");

            val jsonSubs = newPipeSubsParsed["subscriptions"]
            val jsonSubsArray = jsonSubs.asJsonArray;
            val jsonSubsArrayItt = jsonSubsArray.iterator();
            val subs = mutableListOf<String>()
            while(jsonSubsArrayItt.hasNext()) {
                val jsonSubObj = jsonSubsArrayItt.next().asJsonObject;

                if(jsonSubObj.has("url"))
                    subs.add(jsonSubObj["url"].asString);
            }

            navigate(_fragImportSubscriptions, subs);
        }
        catch(ex: Exception) {
            Logger.e(TAG, ex.message, ex);
            UIDialogs.showGeneralErrorDialog(context, "Failed to parse NewPipe Subscriptions", ex);
        }

        /*
        lifecycleScope.launch(Dispatchers.Main) {
            UISlideOverlays.showOverlay(_overlayContainer, "Import Json", "", {},
                SlideUpMenuGroup(context, "What kind of json import is this?", "",
                    SlideUpMenuItem(context, 0, "NewPipe Subscriptions", "", "NewPipeSubs", {
                    }))
            );
        }*/


        return true;
    }


    fun handlePolycentric(url: String): Boolean {
        Logger.i(TAG, "handlePolycentric");
        startActivity(Intent(this, PolycentricImportProfileActivity::class.java).apply { putExtra("url", url) })
        return true;
    }
    private fun readSharedContent(contentPath: String): ByteArray {
        return contentResolver.openInputStream(Uri.parse(contentPath))?.use {
            return it.readBytes();
        } ?: throw IllegalStateException("Opened content was not accessible");
    }

    private fun readSharedFile(filePath: String): ByteArray {
        val dataFile = File(filePath);
        if(!dataFile.exists())
            throw IllegalArgumentException("Opened file does not exist or not permitted");
        val data = dataFile.readBytes();
        return data;
    }

    override fun onBackPressed() {
        Logger.i(TAG, "onBackPressed")

        if(_fragBotBarMenu.onBackPressed())
            return;

        if(_fragVideoDetail.state == VideoDetailFragment.State.MAXIMIZED &&
            _fragVideoDetail.onBackPressed())
            return;


        if(!fragCurrent.onBackPressed())
            closeSegment();
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint();
        Logger.i(TAG, "onUserLeaveHint")

        if(_fragVideoDetail.state == VideoDetailFragment.State.MAXIMIZED || _fragVideoDetail.state == VideoDetailFragment.State.MINIMIZED)
            _fragVideoDetail.onUserLeaveHint();
    }

    override fun onRestart() {
        super.onRestart();
        Logger.i(TAG, "onRestart");

        //Force Portrait on restart
        Logger.i(TAG, "Restarted with state ${_fragVideoDetail.state}");
        if(_fragVideoDetail.state != VideoDetailFragment.State.MAXIMIZED) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, rootView).let { controller ->
                controller.show(WindowInsetsCompat.Type.statusBars());
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
            _fragVideoDetail.onOrientationChanged(OrientationManager.Orientation.PORTRAIT);
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);

        val isStop: Boolean = lifecycle.currentState == Lifecycle.State.CREATED;
        Logger.v(TAG, "onPictureInPictureModeChanged isInPictureInPictureMode=$isInPictureInPictureMode isStop=$isStop")
        _fragVideoDetail?.onPictureInPictureModeChanged(isInPictureInPictureMode, isStop, newConfig);
        Logger.v(TAG, "onPictureInPictureModeChanged Ready");
    }

    override fun onDestroy() {
        super.onDestroy();
        Logger.v(TAG, "onDestroy")

        _orientationManager.disable();

        StateApp.instance.mainAppDestroyed(this);
        StateSaved.instance.setVideoToOpenBlocking(null);
    }


    /**
     * Navigate takes a MainFragment, and makes them the current main visible view
     * A parameter can be provided which becomes available in the onShow of said fragment
     */
    fun navigate(segment: MainFragment, parameter: Any? = null, withHistory: Boolean = true, isBack: Boolean = false) {
        Logger.i(TAG, "Navigate to $segment (parameter=$parameter, withHistory=$withHistory, isBack=$isBack)")

        if(segment != fragCurrent) {
            
            if(segment is VideoDetailFragment) {
                if(_fragContainerVideoDetail.visibility != View.VISIBLE)
                    _fragContainerVideoDetail.visibility = View.VISIBLE;
                when(segment.state) {
                    VideoDetailFragment.State.MINIMIZED -> segment.maximizeVideoDetail()
                    VideoDetailFragment.State.CLOSED -> segment.maximizeVideoDetail()
                    else -> {}
                }
                segment.onShown(parameter, isBack);
                return;
            }
            
            
            fragCurrent.onHide();

            if(segment.isMainView) {
                var transaction = supportFragmentManager.beginTransaction();
                if (segment.topBar != null) {
                    if (segment.topBar != fragCurrent.topBar) {
                        transaction = transaction
                            .show(segment.topBar as Fragment)
                            .replace(R.id.fragment_top_bar, segment.topBar as Fragment);
                        fragCurrent.topBar?.onHide();
                    }
                }
                else if(fragCurrent.topBar != null)
                    transaction.hide(fragCurrent.topBar as Fragment);

                transaction = transaction.replace(R.id.fragment_main, segment);

                val extraBottomDP = if(_fragVideoDetail.state == VideoDetailFragment.State.MINIMIZED) HEIGHT_VIDEO_MINIMIZED_DP else 0f
                if (segment.hasBottomBar) {
                    if (!fragCurrent.hasBottomBar)
                        transaction = transaction.show(_fragBotBarMenu);
                }
                else {
                    if(fragCurrent.hasBottomBar)
                        transaction = transaction.hide(_fragBotBarMenu);
                }
                transaction.commitNow();
            }
            else {
                //Special cases
                if(segment is VideoDetailFragment) {
                    _fragContainerVideoDetail.visibility = View.VISIBLE;
                    _fragVideoDetail.maximizeVideoDetail();
                }

                if(!segment.hasBottomBar) {
                    supportFragmentManager.beginTransaction()
                        .hide(_fragBotBarMenu)
                        .commitNow();
                }
            }

            if(fragCurrent.isHistory && withHistory && _queue.lastOrNull() != fragCurrent)
                _queue.add(Pair(fragCurrent, _parameterCurrent));

            if(segment.isOverlay && !fragCurrent.isOverlay && withHistory)// && fragCurrent.isHistory)
                fragBeforeOverlay = fragCurrent;


            fragCurrent = segment;
            _parameterCurrent = parameter;
        }

        segment.topBar?.onShown(parameter);
        segment.onShown(parameter, isBack);
        onNavigated.emit(segment);
    }

    /**
     * Called when the current segment (main) should be closed, if already at a root view (tab), close application
     * If called with a non-null fragment, it will only close if the current fragment is the provided one
     */
    fun closeSegment(fragment: MainFragment? = null) {
        if(fragment is VideoDetailFragment) {
            fragment.onHide();
            return;
        }

        if((fragment?.isOverlay ?: false) && fragBeforeOverlay != null) {
            navigate(fragBeforeOverlay!!, null, false, true);

        }
        else {
            val last = _queue.lastOrNull();
            if (last != null) {
                _queue.remove(last);
                navigate(last.first, last.second, false, true);
            } else
                finish();
        }
    }

    /**
     * Provides the fragment instance for the provided fragment class
     */
    inline fun <reified T : Fragment> getFragment() : T {
        return when(T::class) {
            HomeFragment::class -> _fragMainHome as T;
            ContentSearchResultsFragment::class -> _fragMainVideoSearchResults as T;
            CreatorSearchResultsFragment::class -> _fragMainCreatorSearchResults as T;
            SuggestionsFragment::class -> _fragMainSuggestions as T;
            VideoDetailFragment::class -> _fragVideoDetail as T;
            MenuBottomBarFragment::class -> _fragBotBarMenu as T;
            GeneralTopBarFragment::class -> _fragTopBarGeneral as T;
            SearchTopBarFragment::class -> _fragTopBarSearch as T;
            CreatorsFragment::class -> _fragMainSubscriptions as T;
            SubscriptionsFeedFragment::class -> _fragMainSubscriptionsFeed as T;
            PlaylistSearchResultsFragment::class -> _fragMainPlaylistSearchResults as T;
            ChannelFragment::class -> _fragMainChannel as T;
            SourcesFragment::class -> _fragMainSources as T;
            PlaylistsFragment::class -> _fragMainPlaylists as T;
            PlaylistFragment::class -> _fragMainPlaylist as T;
            PostDetailFragment::class -> _fragPostDetail as T;
            WatchLaterFragment::class -> _fragWatchlist as T;
            HistoryFragment::class -> _fragHistory as T;
            SourceDetailFragment::class -> _fragSourceDetail as T;
            DownloadsFragment::class -> _fragDownloads as T;
            ImportSubscriptionsFragment::class -> _fragImportSubscriptions as T;
            ImportPlaylistsFragment::class -> _fragImportPlaylists as T;
            BrowserFragment::class -> _fragBrowser as T;
            BuyFragment::class -> _fragBuy as T;
            else -> throw IllegalArgumentException("Fragment type ${T::class.java.name} is not available in MainActivity");
        }
    }


    private fun updateSegmentPaddings() {
        var paddingBottom = 0f;
        if(fragCurrent.hasBottomBar)
            paddingBottom += HEIGHT_MENU_DP;

        _fragContainerOverlay.setPadding(0,0,0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, paddingBottom - HEIGHT_MENU_DP, resources.displayMetrics).toInt());

        if(_fragVideoDetail.state == VideoDetailFragment.State.MINIMIZED)
            paddingBottom += HEIGHT_VIDEO_MINIMIZED_DP;

        _fragContainerMain.setPadding(0,0,0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, paddingBottom, resources.displayMetrics).toInt());
    }



    //TODO: Only calls last handler due to missing request codes on ActivityResultLaunchers.
    private var resultLauncherMap =  mutableMapOf<Int, (ActivityResult)->Unit>();
    private var requestCode: Int? = -1;
    private val resultLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) {
            result: ActivityResult ->
        val handler = synchronized(resultLauncherMap) {
            resultLauncherMap.remove(requestCode);
        }
        if(handler != null)
            handler(result);
    };
    override fun launchForResult(intent: Intent, code: Int, handler: (ActivityResult)->Unit) {
        synchronized(resultLauncherMap) {
            resultLauncherMap[code] = handler;
        }
        requestCode = code;
        resultLauncher.launch(intent);
    }

    companion object {
        private val TAG = "MainActivity"

        fun getTabIntent(context: Context, tab: String) : Intent {
            val sourcesIntent = Intent(context, MainActivity::class.java);
            sourcesIntent.action = "TAB";
            sourcesIntent.putExtra("TAB", tab);
            sourcesIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return sourcesIntent;
        }
    }
}