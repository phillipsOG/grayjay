package com.futo.platformplayer.views.video

import android.content.Context
import android.net.Uri
import android.util.AttributeSet
import android.widget.RelativeLayout
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.api.media.models.streams.VideoMuxedSourceDescriptor
import com.futo.platformplayer.helpers.VideoHelper
import com.futo.platformplayer.api.media.models.streams.sources.*
import com.futo.platformplayer.api.media.models.subtitles.ISubtitleSource
import com.futo.platformplayer.api.media.models.video.IPlatformVideoDetails
import com.futo.platformplayer.api.media.platforms.js.models.sources.JSAudioUrlRangeSource
import com.futo.platformplayer.api.media.platforms.js.models.sources.JSHLSManifestAudioSource
import com.futo.platformplayer.api.media.platforms.js.models.sources.JSVideoUrlRangeSource
import com.futo.platformplayer.constructs.Event1
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.video.PlayerManager
import com.google.android.exoplayer2.*
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.source.SingleSampleMediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.hls.HlsMediaSource
import com.google.android.exoplayer2.text.CueGroup
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.video.VideoSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

abstract class FutoVideoPlayerBase : RelativeLayout {
    private val TAG = "FutoVideoPlayerBase"

    private val TEMP_DIRECTORY = StateApp.instance.getTempDirectory();

    private var _mediaSource: MediaSource? = null;

    var lastVideoSource: IVideoSource? = null
        private set;
    var lastAudioSource: IAudioSource? = null
        private set;

    private var _lastVideoMediaSource: MediaSource? = null;
    private var _lastAudioMediaSource: MediaSource? = null;
    private var _lastSubtitleMediaSource: MediaSource? = null;
    private var _shouldPlaybackRestartOnConnectivity: Boolean = false;
    private val _referenceObject = Object();

    var exoPlayer: PlayerManager? = null
        private set;
    val exoPlayerStateName: String;

    val playing: Boolean get() = exoPlayer?.player?.playWhenReady ?: false;
    val position: Long get() = exoPlayer?.player?.currentPosition ?: 0;
    val duration: Long get() = exoPlayer?.player?.duration ?: 0;

    var isAudioMode: Boolean = false
        private set;

    val onPlayChanged = Event1<Boolean>();
    val onStateChange = Event1<Int>();
    val onPositionDiscontinuity = Event1<Long>();
    val onDatasourceError = Event1<Throwable>();

    private var _didCallSourceChange = false;
    private var _lastState: Int = -1;

    private var _targetTrackVideoHeight = -1;
    private var _targetTrackAudioBitrate = -1;

    private var _toResume = false;

    private val _playerEventListener = object: Player.Listener {
        //TODO: Figure out why this is deprecated, and what the alternative is.
        override fun onPlaybackStateChanged(playbackState: Int) {
            super.onPlaybackStateChanged(playbackState)
            this@FutoVideoPlayerBase.onPlaybackStateChanged(playbackState);

            if(_lastState != playbackState) {
                _lastState = playbackState;
                onStateChange.emit(playbackState);
            }
            when(playbackState) {
                Player.STATE_READY -> {
                    if(!_didCallSourceChange) {
                        _didCallSourceChange = true;
                        onSourceChanged(lastVideoSource, lastAudioSource, _toResume);
                    }
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)
            onPlayChanged.emit(playWhenReady);
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            super.onVideoSizeChanged(videoSize)
            this@FutoVideoPlayerBase.onVideoSizeChanged(videoSize);
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            super.onPositionDiscontinuity(oldPosition, newPosition, reason);
            onPositionDiscontinuity.emit(newPosition.positionMs);
        }

        override fun onCues(cueGroup: CueGroup) {
            super.onCues(cueGroup)
            Logger.i(TAG, "CUE GROUP: ${cueGroup.cues.firstOrNull()?.text}");
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error);
            this@FutoVideoPlayerBase.onPlayerError(error);
        }
    };

    constructor(stateName: String, context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0, defStyleRes: Int = 0) : super(context, attrs, defStyleAttr, defStyleRes) {
        this.exoPlayerStateName = stateName;
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow();

        Logger.v(TAG, "Attached onConnectionAvailable listener.");
        StateApp.instance.onConnectionAvailable.subscribe(_referenceObject) {
            Logger.v(TAG, "onConnectionAvailable");

            val pos = position;
            val dur = duration;
            if (_shouldPlaybackRestartOnConnectivity && abs(pos - dur) > 2000) {
                Logger.i(TAG, "Playback ended due to connection loss, resuming playback since connection is restored.");
                exoPlayer?.player?.playWhenReady = true;
                exoPlayer?.player?.prepare();
                exoPlayer?.player?.play();
            }
        };
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow();

        Logger.i(TAG, "Detached onConnectionAvailable listener.");
        StateApp.instance.onConnectionAvailable.remove(_referenceObject);
    }

    fun switchToVideoMode() {
        Logger.i(TAG, "Switching to Video Mode");
        isAudioMode = false;
        loadSelectedSources(playing, true);
    }
    fun switchToAudioMode() {
        Logger.i(TAG, "Switching to Audio Mode");
        isAudioMode = true;
        loadSelectedSources(playing, true);
    }

    fun seekTo(ms: Long) {
        exoPlayer?.player?.seekTo(ms);
    }
    fun seekToEnd(ms: Long = 0) {
        val duration = Math.max(exoPlayer?.player?.duration ?: 0, 0);
        exoPlayer?.player?.seekTo(Math.max(duration - ms, 0));
    }
    fun seekFromCurrent(ms: Long) {
        val to = Math.max((exoPlayer?.player?.currentPosition ?: 0) + ms, 0);
        exoPlayer?.player?.seekTo(Math.min(to, exoPlayer?.player?.duration ?: to));
    }

    fun changePlayer(newPlayer: PlayerManager?) {
        exoPlayer?.modifyState(exoPlayerStateName, {state -> state.listener = null});
        newPlayer?.modifyState(exoPlayerStateName, {state -> state.listener = _playerEventListener});
        exoPlayer = newPlayer;
    }

    //TODO: Temporary solution, Implement custom track selector without using constraints
    fun selectVideoTrack(height: Int) {
        _targetTrackVideoHeight = height;
        updateTrackSelector();
    }
    fun selectAudioTrack(bitrate: Int) {
        _targetTrackAudioBitrate = bitrate;
        updateTrackSelector();
    }
    private fun updateTrackSelector() {
        var builder = DefaultTrackSelector.Parameters.Builder(context);
        if(_targetTrackVideoHeight > 0) {
            builder = builder
                .setMinVideoSize(0, _targetTrackVideoHeight - 10)
                .setMaxVideoSize(9999, _targetTrackVideoHeight + 10);
        }

        if(_targetTrackAudioBitrate > 0) {
            builder = builder.setMaxAudioBitrate(_targetTrackAudioBitrate);
        }

        val trackSelector = exoPlayer?.player?.trackSelector;
        if(trackSelector != null) {
            trackSelector.parameters = builder.build();
        }
    }

    fun setSource(videoSource: IVideoSource?, audioSource: IAudioSource? = null, play: Boolean = false, keepSubtitles: Boolean = false) {
        swapSources(videoSource, audioSource,false, play, keepSubtitles);
    }
    fun swapSources(videoSource: IVideoSource?, audioSource: IAudioSource?, resume: Boolean = true, play: Boolean = true, keepSubtitles: Boolean = false): Boolean {
        swapSourceInternal(videoSource);
        swapSourceInternal(audioSource);
        if(!keepSubtitles)
            _lastSubtitleMediaSource = null;
        return loadSelectedSources(play, resume);
    }
    fun swapSource(videoSource: IVideoSource?, resume: Boolean = true, play: Boolean = true): Boolean {
        swapSourceInternal(videoSource);
        return loadSelectedSources(play, resume);
    }
    fun swapSource(audioSource: IAudioSource?, resume: Boolean = true, play: Boolean = true): Boolean {
        swapSourceInternal(audioSource);
        return loadSelectedSources(play, resume);
    }

    fun swapSubtitles(scope: CoroutineScope, subtitles: ISubtitleSource?) {
        if(subtitles == null)
            clearSubtitles();
        else {
            if(SUPPORTED_SUBTITLES.contains(subtitles.format?.lowercase())) {
                if (!subtitles.hasFetch) {
                    _lastSubtitleMediaSource = SingleSampleMediaSource.Factory(DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory().setUserAgent(DEFAULT_USER_AGENT)))
                        .createMediaSource(MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitles.url))
                            .setMimeType(subtitles.format)
                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                            .build(),
                            C.TIME_UNSET);
                    loadSelectedSources(true, true);
                } else {
                    scope.launch(Dispatchers.IO) {
                        try {
                            val subUri = subtitles.getSubtitlesURI() ?: return@launch;
                            withContext(Dispatchers.Main) {
                                try {
                                    _lastSubtitleMediaSource = SingleSampleMediaSource.Factory(DefaultDataSource.Factory(context, DefaultHttpDataSource.Factory().setUserAgent(DEFAULT_USER_AGENT)))
                                        .createMediaSource(MediaItem.SubtitleConfiguration.Builder(subUri)
                                            .setMimeType(subtitles.format)
                                            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                                            .build(),
                                            C.TIME_UNSET);
                                    loadSelectedSources(true, true);
                                } catch (e: Throwable) {
                                    Logger.e(TAG, "Failed to load selected sources after subtitle download.", e)
                                }
                            }
                        } catch (e: Throwable) {
                            Logger.e(TAG, "Failed to get subtitles URI.", e)
                        }
                    }
                }
            }
            else
                clearSubtitles();
        }
    }
    private fun clearSubtitles() {
        _lastSubtitleMediaSource = null;
        loadSelectedSources(true, true);
    }


    private fun swapSourceInternal(videoSource: IVideoSource?) {
        when(videoSource) {
            is LocalVideoSource -> swapVideoSourceLocal(videoSource);
            is JSVideoUrlRangeSource -> swapVideoSourceUrlRange(videoSource);
            is IDashManifestSource -> swapVideoSourceDash(videoSource);
            is IHLSManifestSource -> swapVideoSourceHLS(videoSource);
            is IVideoUrlSource -> swapVideoSourceUrl(videoSource);
            null -> _lastVideoMediaSource = null;
            else -> throw IllegalArgumentException("Unsupported video source [${videoSource.javaClass.simpleName}]");
        }
        lastVideoSource = videoSource;
    }
    private fun swapSourceInternal(audioSource: IAudioSource?) {
        when(audioSource) {
            is LocalAudioSource -> swapAudioSourceLocal(audioSource);
            is JSAudioUrlRangeSource -> swapAudioSourceUrlRange(audioSource);
            is JSHLSManifestAudioSource -> swapAudioSourceHLS(audioSource);
            is IAudioUrlSource -> swapAudioSourceUrl(audioSource);
            null -> _lastAudioMediaSource = null;
            else -> throw IllegalArgumentException("Unsupported video source [${audioSource.javaClass.simpleName}]");
        }
        lastAudioSource = audioSource;
    }

    //Video loads
    private fun swapVideoSourceLocal(videoSource: LocalVideoSource) {
        Logger.i(TAG, "Loading VideoSource [Local]");
        val file = File(videoSource.filePath);
        if(!file.exists())
            throw IllegalArgumentException("File for this video does not exist");
        _lastVideoMediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)));
    }
    private fun swapVideoSourceUrlRange(videoSource: JSVideoUrlRangeSource) {
        Logger.i(TAG, "Loading JSVideoUrlRangeSource");
        if(videoSource.hasItag) {
            //Temporary workaround for Youtube
            try {
                _lastVideoMediaSource = VideoHelper.convertItagSourceToChunkedDashSource(videoSource);
                if(_lastVideoMediaSource == null)
                    throw java.lang.IllegalStateException("Dash manifest workaround failed");
                return;
            }
            //If it fails to create the dash workaround, fallback to standard progressive
            catch(ex: Exception) {
                Logger.i(TAG, "Dash manifest workaround failed for video, falling back to progressive due to ${ex.message}");
                _lastVideoMediaSource = ProgressiveMediaSource.Factory(videoSource.getHttpDataSourceFactory())
                    .createMediaSource(MediaItem.fromUri(videoSource.getVideoUrl()));
                return;
            }
        }
        else throw IllegalArgumentException("source without itag data...");
    }
    private fun swapVideoSourceUrl(videoSource: IVideoUrlSource) {
        Logger.i(TAG, "Loading VideoSource [Url]");
        _lastVideoMediaSource = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT))
            .createMediaSource(MediaItem.fromUri(videoSource.getVideoUrl()));
    }
    private fun swapVideoSourceDash(videoSource: IDashManifestSource) {
        Logger.i(TAG, "Loading VideoSource [Dash]");
        _lastVideoMediaSource = if (videoSource != null) DashMediaSource.Factory(DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT))
            .createMediaSource(MediaItem.fromUri(videoSource.url));
        else null;
    }
    private fun swapVideoSourceHLS(videoSource: IHLSManifestSource) {
        Logger.i(TAG, "Loading VideoSource [HLS]");
        _lastVideoMediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT))
            .createMediaSource(MediaItem.fromUri(videoSource.url));
    }

    //Audio loads
    private fun swapAudioSourceLocal(audioSource: LocalAudioSource) {
        Logger.i(TAG, "Loading AudioSource [Local]");
        val file = File(audioSource.filePath);
        if(!file.exists())
            throw IllegalArgumentException("File for this audio does not exist");
        _lastAudioMediaSource = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context))
            .createMediaSource(MediaItem.fromUri(Uri.fromFile(file)));
    }
    private fun swapAudioSourceUrlRange(audioSource: JSAudioUrlRangeSource) {
        Logger.i(TAG, "Loading JSAudioUrlRangeSource");
        if(audioSource.hasItag) {
            try {
                _lastAudioMediaSource = VideoHelper.convertItagSourceToChunkedDashSource(audioSource);
                if(_lastAudioMediaSource == null)
                    throw java.lang.IllegalStateException("Missing required parameters for dash workaround?");
                return;
            }
            //If it fails to create the dash workaround, fallback to standard progressive
            catch(ex: Exception) {
                Logger.i(TAG, "Dash manifest workaround failed for audio, falling back to progressive due to ${ex.message}");
                _lastAudioMediaSource = ProgressiveMediaSource.Factory(audioSource.getHttpDataSourceFactory())
                    .createMediaSource(MediaItem.fromUri((audioSource as IAudioUrlSource).getAudioUrl()));
                return;
            }
        }
        else throw IllegalArgumentException("source without itag data...")
    }
    private fun swapAudioSourceUrl(audioSource: IAudioUrlSource) {
        Logger.i(TAG, "Loading AudioSource [Url]");
        _lastAudioMediaSource = ProgressiveMediaSource.Factory(DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT))
            .createMediaSource(MediaItem.fromUri(audioSource.getAudioUrl()));
    }
    private fun swapAudioSourceHLS(audioSource: IHLSManifestAudioSource) {
        Logger.i(TAG, "Loading AudioSource [HLS]");
        _lastAudioMediaSource = HlsMediaSource.Factory(DefaultHttpDataSource.Factory()
            .setUserAgent(DEFAULT_USER_AGENT))
            .createMediaSource(MediaItem.fromUri(audioSource.url));
    }


    //Prefered source selection
    fun getPreferredVideoSource(video: IPlatformVideoDetails, targetPixels: Int = -1): IVideoSource? {
        val usePreview = false;
        if(usePreview) {
            if(video.preview != null && video.preview is VideoMuxedSourceDescriptor)
                return (video.preview as VideoMuxedSourceDescriptor).videoSources.last();
            return null;
        }
        else if(video.live != null)
            return video.live;
        else if(video.dash != null)
            return video.dash;
        else if(video.hls != null)
            return video.hls;
        else
            return VideoHelper.selectBestVideoSource(video.video, targetPixels, PREFERED_VIDEO_CONTAINERS)
    }
    fun getPreferredAudioSource(video: IPlatformVideoDetails, preferredLanguage: String?): IAudioSource? {
        return VideoHelper.selectBestAudioSource(video.video, PREFERED_AUDIO_CONTAINERS, preferredLanguage);
    }

    private fun loadSelectedSources(play: Boolean, resume: Boolean): Boolean {
        val sourceVideo = if(!isAudioMode || _lastAudioMediaSource == null) _lastVideoMediaSource else null;
        val sourceAudio = _lastAudioMediaSource;
        val sourceSubs = _lastSubtitleMediaSource;

        val sources = listOf(sourceVideo, sourceAudio, sourceSubs).filter { it != null }.map { it!! }.toTypedArray()

        beforeSourceChanged();

        _mediaSource = if(sources.size == 1) {
            Logger.i(TAG, "Using single source mode")
            (sourceVideo ?: sourceAudio);
        }
        else if(sources.size >  1) {
            Logger.i(TAG, "Using multi source mode ${sources.size}")
            MergingMediaSource(true, *sources);
        }
        else {
            Logger.i(TAG, "Using no sources loaded");
            stop();
            return false;
        }

        reloadMediaSource(play, resume);
        return true;
    }

    private fun reloadMediaSource(play: Boolean = false, resume: Boolean = true) {
        val player = exoPlayer
        if (player == null)
            return;

        val positionBefore = player.player.currentPosition;
        if(_mediaSource != null) {
            player.player.setMediaSource(_mediaSource!!);
            _toResume = resume;
            _didCallSourceChange = false;
            player.player.prepare()
            player.player.playWhenReady = play;
            if(resume)
                seekTo(positionBefore);
            else
                seekTo(0);
            this.onSourceChanged(lastVideoSource, lastAudioSource, resume);
        }
        else
            player.player?.stop();
    }

    fun clear() {
        exoPlayer?.player?.stop();
        exoPlayer?.player?.clearMediaItems();
    }

    fun stop(){
        exoPlayer?.player?.stop();
    }
    fun pause(){
        exoPlayer?.player?.pause();
    }
    open fun play(){
        exoPlayer?.player?.play();
    }

    fun setVolume(volume: Float) {
        exoPlayer?.setVolume(volume);
    }

    protected open fun onPlayerError(error: PlaybackException) {
        when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> {
                onDatasourceError.emit(error);
            }
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED -> {
                Logger.i(TAG, "IO error, set _shouldPlaybackRestartOnConnectivity=true");
                _shouldPlaybackRestartOnConnectivity = true;
            }
        }
    }

    protected open fun onVideoSizeChanged(videoSize: VideoSize) {

    }
    protected open fun beforeSourceChanged() {

    }
    protected open fun onSourceChanged(videoSource: IVideoSource?, audioSource: IAudioSource? = null, resume: Boolean = true) { }

    protected open fun onPlaybackStateChanged(playbackState: Int) {
        if (_shouldPlaybackRestartOnConnectivity && playbackState == ExoPlayer.STATE_READY) {
            Logger.i(TAG, "_shouldPlaybackRestartOnConnectivity=false");
            _shouldPlaybackRestartOnConnectivity = false;
        }


    }

    companion object {
        val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:91.0) Gecko/20100101 Firefox/91.0";

        val PREFERED_VIDEO_CONTAINERS = arrayOf("video/mp4", "video/webm", "video/3gpp");
        val PREFERED_AUDIO_CONTAINERS = arrayOf("audio/mp3", "audio/mp4", "audio/webm", "audio/opus");

        val SUPPORTED_SUBTITLES = hashSetOf("text/vtt", "application/x-subrip");
    }
}