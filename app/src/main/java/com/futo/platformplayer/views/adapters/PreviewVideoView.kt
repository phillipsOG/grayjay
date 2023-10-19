package com.futo.platformplayer.views.adapters

import android.animation.ObjectAnimator
import android.content.Context
import android.content.res.Resources
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.futo.platformplayer.*
import com.futo.platformplayer.api.media.PlatformID
import com.futo.platformplayer.api.media.models.PlatformAuthorLink
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.models.contents.IPlatformContentDetails
import com.futo.platformplayer.api.media.models.video.IPlatformVideo
import com.futo.platformplayer.api.media.models.video.IPlatformVideoDetails
import com.futo.platformplayer.constructs.Event1
import com.futo.platformplayer.constructs.Event2
import com.futo.platformplayer.constructs.TaskHandler
import com.futo.platformplayer.images.GlideHelper.Companion.crossfade
import com.futo.platformplayer.images.GlideHelper.Companion.loadThumbnails
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.polycentric.PolycentricCache
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StateDownloads
import com.futo.platformplayer.video.PlayerManager
import com.futo.platformplayer.views.others.CreatorThumbnail
import com.futo.platformplayer.views.FeedStyle
import com.futo.platformplayer.views.platform.PlatformIndicator
import com.futo.platformplayer.views.video.FutoThumbnailPlayer


open class PreviewVideoView : LinearLayout {
    protected val _feedStyle : FeedStyle;

    protected val _imageVideo: ImageView
    protected val _imageChannel: ImageView?
    protected val _creatorThumbnail: CreatorThumbnail?
    protected val _imageNeopassChannel: ImageView?;
    protected val _platformIndicator: PlatformIndicator;
    protected val _textVideoName: TextView
    protected val _textChannelName: TextView
    protected val _textVideoMetadata: TextView
    protected val _containerDuration: LinearLayout;
    protected val _textVideoDuration: TextView;
    protected var _playerVideoThumbnail: FutoThumbnailPlayer? = null;
    protected val _containerLive: LinearLayout;
    protected val _playerContainer: FrameLayout;
    protected var _neopassAnimator: ObjectAnimator? = null;
    protected val _layoutDownloaded: FrameLayout;

    protected val _button_add_to_queue : View;
    protected val _button_add_to : View;

    protected val _exoPlayer: PlayerManager?;

    private val _taskLoadValidClaims = TaskHandler<PlatformID, PolycentricCache.CachedOwnedClaims>(StateApp.instance.scopeGetter,
        { PolycentricCache.instance.getValidClaimsAsync(it).await() })
        .success { it -> updateClaimsLayout(it, animate = true) }
        .exception<Throwable> {
            Logger.w(TAG, "Failed to load claims.", it);
        };

    val onVideoClicked = Event2<IPlatformVideo, Long>();
    val onChannelClicked = Event1<PlatformAuthorLink>();
    val onAddToClicked = Event1<IPlatformVideo>();
    val onAddToQueueClicked = Event1<IPlatformVideo>();

    var currentVideo: IPlatformVideo? = null
        private set

    val content: IPlatformContent? get() = currentVideo;

    constructor(context: Context, feedStyle : FeedStyle, exoPlayer: PlayerManager? = null) : super(context) {
        inflate(feedStyle);
        _feedStyle = feedStyle;
        val playerContainer = findViewById<FrameLayout>(R.id.player_container);

        val displayMetrics = Resources.getSystem().displayMetrics;
        val width: Double = if (feedStyle == FeedStyle.PREVIEW) {
            displayMetrics.widthPixels.toDouble();
        } else {
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 177.0f, displayMetrics).toDouble();
        };

        /*
        val ar = 16.0 / 9.0;
        var height = width / ar;
        height += TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6.0f, displayMetrics);

        val layoutParams = playerContainer.layoutParams;
        layoutParams.height = height.roundToInt();
        playerContainer.layoutParams = layoutParams;*/

        //Logger.i(TAG, "Player container height calculated to be $height.");


        _playerContainer = findViewById(R.id.player_container);
        _imageVideo = findViewById(R.id.image_video_thumbnail)
        _imageChannel = findViewById(R.id.image_channel_thumbnail);
        _creatorThumbnail = findViewById(R.id.creator_thumbnail);
        _platformIndicator = findViewById(R.id.thumbnail_platform);
        _textVideoName = findViewById(R.id.text_video_name)
        _textChannelName = findViewById(R.id.text_channel_name)
        _textVideoMetadata = findViewById(R.id.text_video_metadata)
        _textVideoDuration = findViewById(R.id.thumbnail_duration);
        _containerDuration = findViewById(R.id.thumbnail_duration_container);
        _containerLive = findViewById(R.id.thumbnail_live_container);
        _button_add_to_queue = findViewById(R.id.button_add_to_queue);
        _button_add_to = findViewById(R.id.button_add_to);
        _imageNeopassChannel = findViewById(R.id.image_neopass_channel);
        _layoutDownloaded = findViewById(R.id.layout_downloaded);

        this._exoPlayer = exoPlayer

        setOnClickListener { onOpenClicked()  };
        _imageChannel.setOnClickListener { currentVideo?.let { onChannelClicked.emit(it.author) }  };
        _textChannelName.setOnClickListener { currentVideo?.let { onChannelClicked.emit(it.author) }  };
        _textVideoMetadata.setOnClickListener { currentVideo?.let { onChannelClicked.emit(it.author) }  };
        _button_add_to.setOnClickListener { currentVideo?.let { onAddToClicked.emit(it) } };
        _button_add_to_queue.setOnClickListener { currentVideo?.let { onAddToQueueClicked.emit(it) } };

    }

    protected open fun inflate(feedStyle: FeedStyle) {
        inflate(context, when(feedStyle) {
            FeedStyle.PREVIEW -> R.layout.list_video_preview
            else -> R.layout.list_video_thumbnail
        }, this)
    }

    protected open fun onOpenClicked() {
        currentVideo?.let {
            val currentPlayer = _playerVideoThumbnail;
            var sec = if(currentPlayer != null && currentPlayer.playing)
                (currentPlayer.position / 1000).toLong();
            else 0L;
            onVideoClicked.emit(it, sec);
        }
    }


    open fun bind(content: IPlatformContent) {
        _taskLoadValidClaims.cancel();

        val cachedClaims = PolycentricCache.instance.getCachedValidClaims(content.author.id);
        if (cachedClaims != null) {
            updateClaimsLayout(cachedClaims, animate = false);
        } else {
            updateClaimsLayout(null, animate = false);
            _taskLoadValidClaims.run(content.author.id);
        }

        isClickable = true;

        val isPlanned = (content.datetime?.getNowDiffSeconds() ?: 0) < 0;

        stopPreview();

        if(_imageChannel != null)
            Glide.with(_imageChannel)
                .load(content.author.thumbnail)
                .placeholder(R.drawable.placeholder_channel_thumbnail)
                .into(_imageChannel);

        _imageChannel?.clipToOutline = true;

        _textVideoName.text = content.name;
        _textChannelName.text = content.author.name
        _layoutDownloaded.visibility = if (StateDownloads.instance.isDownloaded(content.id)) VISIBLE else GONE;

        _platformIndicator.setPlatformFromClientID(content.id.pluginId);

        var metadata = ""
        if (content is IPlatformVideo && content.viewCount > 0) {
            if(content.isLive)
                metadata += "${content.viewCount.toHumanNumber()} watching • ";
            else
                metadata += "${content.viewCount.toHumanNumber()} views • ";
        }

        var timeMeta = "";
        if(isPlanned) {
            val ago = content.datetime?.toHumanNowDiffString(true) ?: ""
            timeMeta = "available in " + ago;
        }
        else {
            val ago = content.datetime?.toHumanNowDiffString() ?: ""
            timeMeta = if (ago.isNotBlank()) ago + " ago" else ago;
        }

        if(content is IPlatformVideo) {
            val video = content;

            currentVideo = video

            _imageVideo.loadThumbnails(video.thumbnails, true) {
                it.placeholder(R.drawable.placeholder_video_thumbnail)
                    .crossfade()
                    .into(_imageVideo);
            };

            if(!isPlanned)
                _textVideoDuration.text = video.duration.toHumanTime(false);
            else
                _textVideoDuration.text = "Planned";

            _playerVideoThumbnail?.setLive(video.isLive);
            if(!isPlanned && video.isLive) {
                _containerDuration.visibility = GONE;
                _containerLive.visibility = VISIBLE;
                timeMeta = "LIVE"
            }
            else {
                _containerLive.visibility = GONE;
                _containerDuration.visibility = VISIBLE;
            }
        }
        else {
            currentVideo = null;
            _imageVideo.setImageResource(0);
            _containerDuration.visibility = GONE;
            _containerLive.visibility = GONE;
        }
        _textVideoMetadata.text = metadata + timeMeta;
    }

    open fun preview(video: IPlatformContentDetails?, paused: Boolean) {
        if(video == null)
            return;
        Logger.i(TAG, "Previewing");
        if(video !is IPlatformVideoDetails)
            throw IllegalStateException("Expected VideoDetails");

        if(_feedStyle == FeedStyle.THUMBNAIL)
            return;

        val exoPlayer = _exoPlayer ?: return;

        Log.v(TAG, "video preview start playing" + video.name);

        val playerVideoThumbnail = FutoThumbnailPlayer(context);
        playerVideoThumbnail.layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        playerVideoThumbnail.setPlayer(exoPlayer);
        if(!exoPlayer.currentState.muted)
            playerVideoThumbnail.unmute();
        else
            playerVideoThumbnail.mute();

        playerVideoThumbnail.setTempDuration(video.duration, false);
        playerVideoThumbnail.setPreview(video);
        _playerContainer.addView(playerVideoThumbnail);
        _playerVideoThumbnail = playerVideoThumbnail;
    }
    fun stopPreview() {
        if(_feedStyle == FeedStyle.THUMBNAIL)
            return;
        Log.v(TAG, "video preview stopping=" + currentVideo?.name);

        val playerVideoThumbnail = _playerVideoThumbnail;
        if (playerVideoThumbnail != null) {
            playerVideoThumbnail.stop();
            playerVideoThumbnail.setPlayer(null);
            _playerContainer.removeView(playerVideoThumbnail);
            _playerVideoThumbnail = null;
        }

        Log.v(TAG, "video preview playing and made invisible" + currentVideo?.name)
    }
    fun pausePreview() {
        if(_feedStyle == FeedStyle.THUMBNAIL)
            return;
        Log.v(TAG, "video preview pausing " + currentVideo?.name)

        _playerVideoThumbnail?.pause();

        Log.v(TAG, "video preview paused " + currentVideo?.name)
    }
    fun resumePreview() {
        if(_feedStyle == FeedStyle.THUMBNAIL)
            return;
        Log.v(TAG, "video preview resuming " + currentVideo?.name)

        _playerVideoThumbnail?.play();

        Log.v(TAG, "video preview resumed" + currentVideo?.name)
    }


    //Events
    fun setMuteChangedListener(callback : (FutoThumbnailPlayer, Boolean) -> Unit) {
        _playerVideoThumbnail?.setMuteChangedListener(callback);
    }

    private fun updateClaimsLayout(claims: PolycentricCache.CachedOwnedClaims?, animate: Boolean) {
        _neopassAnimator?.cancel();
        _neopassAnimator = null;

        val harborAvailable = claims != null && !claims.ownedClaims.isNullOrEmpty();
        if (harborAvailable) {
            _imageNeopassChannel?.visibility = View.VISIBLE
            if (animate) {
                _neopassAnimator = ObjectAnimator.ofFloat(_imageNeopassChannel, "alpha", 0.0f, 1.0f).setDuration(500)
                _neopassAnimator?.start()
            }
        } else {
            _imageNeopassChannel?.visibility = View.GONE
        }

        _creatorThumbnail?.setHarborAvailable(harborAvailable, animate)
    }

    companion object {
        private val TAG = "VideoPreviewViewHolder"
    }
}
