package com.futo.platformplayer.helpers

import android.net.Uri
import com.futo.platformplayer.api.media.models.streams.IVideoSourceDescriptor
import com.futo.platformplayer.api.media.models.streams.VideoUnMuxedSourceDescriptor
import com.futo.platformplayer.api.media.models.streams.sources.IAudioSource
import com.futo.platformplayer.api.media.models.streams.sources.IAudioUrlSource
import com.futo.platformplayer.api.media.models.streams.sources.IVideoSource
import com.futo.platformplayer.api.media.models.streams.sources.IVideoUrlSource
import com.futo.platformplayer.api.media.models.video.IPlatformVideoDetails
import com.futo.platformplayer.api.media.platforms.js.models.sources.JSAudioUrlRangeSource
import com.futo.platformplayer.api.media.platforms.js.models.sources.JSVideoUrlRangeSource
import com.futo.platformplayer.logging.Logger
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.dash.DashMediaSource
import com.google.android.exoplayer2.source.dash.manifest.DashManifestParser
import com.google.android.exoplayer2.upstream.ResolvingDataSource

class VideoHelper {
    companion object {

        fun isDownloadable(detail: IPlatformVideoDetails) =
            (detail.video.videoSources.any { isDownloadable(it) }) ||
                    (if (detail is VideoUnMuxedSourceDescriptor) detail.audioSources.any { isDownloadable(it) } else false);
        fun isDownloadable(source: IVideoSource) = source is IVideoUrlSource;
        fun isDownloadable(source: IAudioSource) = source is IAudioUrlSource;

        fun selectBestVideoSource(desc: IVideoSourceDescriptor, desiredPixelCount : Int, prefContainers : Array<String>) : IVideoSource? = selectBestVideoSource(desc.videoSources.toList(), desiredPixelCount, prefContainers);
        fun selectBestVideoSource(sources: Iterable<IVideoSource>, desiredPixelCount : Int, prefContainers : Array<String>) : IVideoSource? {
            val targetVideo = if(desiredPixelCount > 0)
                sources.toList()
                    .sortedBy { x -> Math.abs(x.height * x.width - desiredPixelCount) }
                    .firstOrNull();
            else
                sources.toList()
                    .lastOrNull();

            val hasPriority = sources.any { it.priority };

            val targetPixelCount = if(targetVideo != null) targetVideo.width * targetVideo.height else desiredPixelCount;
            val altSources = if(hasPriority) {
                sources.filter { it.priority }.sortedBy { x -> Math.abs(x.height * x.width - targetPixelCount) };
            } else {
                sources.filter { it.height == (targetVideo?.height ?: 0) };
            }

            var bestSource = altSources.firstOrNull();
            for (prefContainer in prefContainers) {
                val betterSource = altSources.firstOrNull { it.container == prefContainer };
                if(betterSource != null) {
                    bestSource = betterSource;
                    break;
                }
            }

            return bestSource;
        }


        fun selectBestAudioSource(desc: IVideoSourceDescriptor, prefContainers : Array<String>, prefLanguage: String? = null, targetBitrate: Long? = null) : IAudioSource? {
            if(!desc.isUnMuxed)
                return null;
            return selectBestAudioSource((desc as VideoUnMuxedSourceDescriptor).audioSources.toList(), prefContainers, prefLanguage);
        }
        fun selectBestAudioSource(altSources : Iterable<IAudioSource>, prefContainers : Array<String>, preferredLanguage: String? = null, targetBitrate: Long? = null) : IAudioSource? {
            val languageToFilter = if(preferredLanguage != null && altSources.any { it.language == preferredLanguage })
                preferredLanguage
            else if(preferredLanguage == null) null
            else "Unknown";

            var usableSources = if(languageToFilter != null && altSources.any { it.language == languageToFilter })
                altSources.filter { it.language == languageToFilter }.sortedBy { it.bitrate }.toList();
            else altSources.sortedBy { it.bitrate };

            if(usableSources.any { it.priority })
                usableSources = usableSources.filter { it.priority };


            var bestSource = if(targetBitrate != null)
                usableSources.minByOrNull { Math.abs(it.bitrate - targetBitrate) };
            else
                usableSources.lastOrNull();

            for (prefContainer in prefContainers) {
                val betterSources = usableSources.filter { it.container == prefContainer };
                val betterSource = if(targetBitrate != null)
                    betterSources.minByOrNull { Math.abs(it.bitrate - targetBitrate) };
                else
                    betterSources.lastOrNull();

                if(betterSource != null) {
                    bestSource = betterSource;
                    break;
                }
            }
            return bestSource;
        }

        var breakOnce = hashSetOf<String>()
        fun convertItagSourceToChunkedDashSource(videoSource: JSVideoUrlRangeSource) : MediaSource {
            var urlToUse = videoSource.getVideoUrl();
            /*
            //TODO: REMOVE THIS, PURPOSELY 403s
            if(urlToUse.contains("sig=") && !breakOnce.contains(urlToUse)) {
                breakOnce.add(urlToUse);
                val sigIndex = urlToUse.indexOf("sig=");
                urlToUse = urlToUse.substring(0, sigIndex) + "sig=0" + urlToUse.substring(sigIndex + 4);
            }*/

            val manifestConfig = ProgressiveDashManifestCreator.fromVideoProgressiveStreamingUrl(urlToUse,
                videoSource.duration * 1000,
                videoSource.container,
                videoSource.itagId ?: 1,
                videoSource.codec,
                videoSource.bitrate,
                videoSource.width,
                videoSource.height,
                -1,
                videoSource.indexStart ?: 0,
                videoSource.indexEnd ?: 0,
                videoSource.initStart ?: 0,
                videoSource.initEnd ?: 0
            );

            val manifest = DashManifestParser().parse(Uri.parse(""), manifestConfig.byteInputStream());

            return DashMediaSource.Factory(ResolvingDataSource.Factory(videoSource.getHttpDataSourceFactory(), ResolvingDataSource.Resolver { dataSpec ->
                Logger.v("PLAYBACK", "Video REQ Range [" + dataSpec.position + "-" + (dataSpec.position + dataSpec.length) + "](" + dataSpec.length + ")", null);
                return@Resolver dataSpec;
            }))
                .createMediaSource(manifest,
                    MediaItem.Builder()
                        .setUri(Uri.parse(videoSource.getVideoUrl()))
                        .build())
        }

        fun convertItagSourceToChunkedDashSource(audioSource: JSAudioUrlRangeSource) : MediaSource {
            val manifestConfig = ProgressiveDashManifestCreator.fromAudioProgressiveStreamingUrl(audioSource.getAudioUrl(),
                audioSource.duration?.times(1000) ?: 0,
                audioSource.container,
                audioSource.audioChannels,
                audioSource.itagId ?: 1,
                audioSource.codec,
                audioSource.bitrate,
                -1,
                audioSource.indexStart ?: 0,
                audioSource.indexEnd ?: 0,
                audioSource.initStart ?: 0,
                audioSource.initEnd ?: 0
            );

            val manifest = DashManifestParser().parse(Uri.parse(""), manifestConfig.byteInputStream());

            return DashMediaSource.Factory(ResolvingDataSource.Factory(audioSource.getHttpDataSourceFactory(), ResolvingDataSource.Resolver { dataSpec ->
                Logger.v("PLAYBACK", "Audio REQ Range [" + dataSpec.position + "-" + (dataSpec.position + dataSpec.length) + "](" + dataSpec.length + ")", null);
                return@Resolver dataSpec;
            }))
                .createMediaSource(manifest,
                    MediaItem.Builder()
                        .setUri(Uri.parse(audioSource.getAudioUrl()))
                        .build())
        }
    }
}
