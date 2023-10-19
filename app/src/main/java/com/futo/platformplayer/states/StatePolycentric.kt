package com.futo.platformplayer.states

import android.content.Context
import android.content.Intent
import com.futo.platformplayer.R
import com.futo.platformplayer.Settings
import com.futo.platformplayer.UIDialogs
import com.futo.platformplayer.activities.PolycentricHomeActivity
import com.futo.platformplayer.api.media.PlatformID
import com.futo.platformplayer.api.media.models.PlatformAuthorLink
import com.futo.platformplayer.api.media.models.comments.IPlatformComment
import com.futo.platformplayer.api.media.models.comments.PolycentricPlatformComment
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.models.contents.PlatformContentPlaceholder
import com.futo.platformplayer.api.media.models.ratings.RatingLikeDislikes
import com.futo.platformplayer.api.media.structures.DedupContentPager
import com.futo.platformplayer.api.media.structures.EmptyPager
import com.futo.platformplayer.api.media.structures.IAsyncPager
import com.futo.platformplayer.api.media.structures.IPager
import com.futo.platformplayer.api.media.structures.MultiChronoContentPager
import com.futo.platformplayer.api.media.structures.PlaceholderPager
import com.futo.platformplayer.api.media.structures.RefreshDedupContentPager
import com.futo.platformplayer.api.media.structures.RefreshDistributionContentPager
import com.futo.platformplayer.awaitFirstDeferred
import com.futo.platformplayer.dp
import com.futo.platformplayer.fragment.mainactivity.main.PolycentricProfile
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.polycentric.PolycentricCache
import com.futo.platformplayer.resolveChannelUrl
import com.futo.platformplayer.selectBestImage
import com.futo.platformplayer.stores.FragmentedStorage
import com.futo.platformplayer.stores.StringStorage
import com.futo.polycentric.core.*
import com.google.protobuf.ByteString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import userpackage.Protocol
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

class StatePolycentric {
    private data class LikeDislikeEntry(val unixMilliseconds: Long, val hasLiked: Boolean, val hasDisliked: Boolean);

    var processHandle: ProcessHandle? = null; private set;
    private var _likeDislikeMap = hashMapOf<String, LikeDislikeEntry>()
    private val _activeProcessHandle = FragmentedStorage.get<StringStorage>("activeProcessHandle");

    fun load(context: Context) {
        val db = SqlLiteDbHelper(context);
        Store.initializeSqlLiteStore(db);

        val activeProcessHandleString = _activeProcessHandle.value;
        if (activeProcessHandleString.isNotEmpty()) {
            val system = PublicKey.fromProto(Protocol.PublicKey.parseFrom(activeProcessHandleString.base64ToByteArray()));
            setProcessHandle(Store.instance.getProcessSecret(system)?.toProcessHandle());
        }
    }

    fun getProcessHandles(): List<ProcessHandle> {
        return Store.instance.getProcessSecrets().map { it.toProcessHandle(); };
    }

    fun setProcessHandle(processHandle: ProcessHandle?) {
        this.processHandle = processHandle;

        if (processHandle != null) {
            _activeProcessHandle.setAndSave(processHandle.system.toProto().toByteArray().toBase64());

            val newMap = hashMapOf<String, LikeDislikeEntry>()
            Store.instance.enumerateSignedEvents(processHandle.system, ContentType.OPINION) {
                try {
                    for (ref in it.event.references) {
                        val refd = ref.toByteArray().toBase64();
                        val e = newMap[refd];
                        if (e == null || it.event.unixMilliseconds!! > e.unixMilliseconds) {
                            val data = it.event.lwwElement?.value ?: continue;
                            newMap[refd] = LikeDislikeEntry(it.event.unixMilliseconds!!, Opinion(data) == Opinion.like, Opinion(data) == Opinion.dislike);
                        }
                    }
                } catch (e: Throwable) {
                    Logger.w(TAG, "Failed to get opinion, skipped.")
                }
            }

            _likeDislikeMap = newMap
        } else {
            _activeProcessHandle.setAndSave("");
            _likeDislikeMap = hashMapOf()
        }
    }

    fun updateLikeMap(ref: Protocol.Reference, hasLiked: Boolean, hasDisliked: Boolean) {
        _likeDislikeMap[ref.toByteArray().toBase64()] = LikeDislikeEntry(System.currentTimeMillis(), hasLiked, hasDisliked);
    }

    fun hasDisliked(ref: Protocol.Reference): Boolean {
        val entry = _likeDislikeMap[ref.toByteArray().toBase64()] ?: return false;
        return entry.hasDisliked;
    }

    fun hasLiked(ref: Protocol.Reference): Boolean {
        val entry = _likeDislikeMap[ref.toByteArray().toBase64()] ?: return false;
        return entry.hasLiked;
    }

    fun requireLogin(context: Context, text: String, action: (processHandle: ProcessHandle) -> Unit) {
        val p = processHandle;
        if (p == null) {
            Logger.i(TAG, "requireLogin preventPictureInPicture.emit()");
            StateApp.instance.preventPictureInPicture.emit();
            UIDialogs.showDialog(context, R.drawable.ic_login,
                text, null, null,
                1,
                UIDialogs.Action("Cancel", { }, UIDialogs.ActionStyle.ACCENT),
                UIDialogs.Action("OK", {
                    context.startActivity(Intent(context, PolycentricHomeActivity::class.java));
                }, UIDialogs.ActionStyle.PRIMARY)
            );
        } else {
            action(p);
        }
    }

    fun getChannelContent(profile: PolycentricProfile, isSubscriptionOptimized: Boolean = false, channelConcurrency: Int = -1, ignorePlugins: List<String>? = null): IPager<IPlatformContent> {
        //TODO: Currently abusing subscription concurrency for parallelism
        val concurrency = if (channelConcurrency == -1) Settings.instance.subscriptions.getSubscriptionsConcurrency() else channelConcurrency;
        val pagers = profile.ownedClaims.groupBy { it.claim.claimType }.mapNotNull {
            //TODO: Deduplicate once multiple urls in single claim is supported
            return@mapNotNull it.value.firstOrNull();
        }.mapNotNull {
            val url = it.claim.resolveChannelUrl() ?: return@mapNotNull null;
            if (!StatePlatform.instance.hasEnabledChannelClient(url)) {
                return@mapNotNull null;
            }

            return@mapNotNull StatePlatform.instance.getChannelContent(url, isSubscriptionOptimized, concurrency, ignorePlugins);
        }.toTypedArray();

        val pager = MultiChronoContentPager(pagers);
        pager.initialize();
        return DedupContentPager(pager, StatePlatform.instance.getEnabledClients().map { it.id });
    }

    fun getChannelContent(scope: CoroutineScope, profile: PolycentricProfile, isSubscriptionOptimized: Boolean = false, channelConcurrency: Int = -1): IPager<IPlatformContent>? {
        //TODO: Currently abusing subscription concurrency for parallelism
        val concurrency = if (channelConcurrency == -1) Settings.instance.subscriptions.getSubscriptionsConcurrency() else channelConcurrency;
        val deferred = profile.ownedClaims.groupBy { it.claim.claimType }
            .mapNotNull {
                //TODO: Deduplicate once multiple urls in single claim is supported
                return@mapNotNull it.value.firstOrNull();
            }.mapNotNull {
                val url = it.claim.resolveChannelUrl() ?: return@mapNotNull null;
                val client = StatePlatform.instance.getChannelClientOrNull(url) ?: return@mapNotNull null;

                return@mapNotNull Pair(client, scope.async(Dispatchers.IO) {
                    try {
                        return@async StatePlatform.instance.getChannelContent(url, isSubscriptionOptimized, concurrency);
                    } catch (ex: Throwable) {
                        Logger.e(TAG, "getChannelContent", ex);
                        return@async null;
                    }
                })
            }
            .groupBy { it.first.name }
            .map { it.value.first() };
        val finishedPager: Pair<Deferred<IPager<IPlatformContent>?>, IPager<IPlatformContent>?> = (if(deferred.isEmpty()) null else runBlocking {
                deferred.map { it.second }.awaitFirstDeferred();
            }) ?: return null;

        val toAwait = deferred.filter { it.second != finishedPager.first };
        return RefreshDedupContentPager(RefreshDistributionContentPager(
            listOf(finishedPager.second!!),
            toAwait.map { it.second },
            toAwait.map { PlaceholderPager(5) { PlatformContentPlaceholder(it.first.id) } }),
            StatePlatform.instance.getEnabledClients().map { it.id }
        );
    }
    suspend fun getChannelContent(profile: PolycentricProfile): IPager<IPlatformContent> {
        return withContext(Dispatchers.IO) {
            getChannelContent(this, profile) ?: EmptyPager();
        }
    }

    suspend fun getCommentPager(contextUrl: String, reference: Protocol.Reference): IPager<IPlatformComment> {
        val response = ApiMethods.getQueryReferences(PolycentricCache.SERVER, reference, null,
            Protocol.QueryReferencesRequestEvents.newBuilder()
                .setFromType(ContentType.POST.value)
                .addAllCountLwwElementReferences(arrayListOf(
                    Protocol.QueryReferencesRequestCountLWWElementReferences.newBuilder()
                        .setFromType(ContentType.OPINION.value)
                        .setValue(ByteString.copyFrom(Opinion.like.data))
                        .build(),
                    Protocol.QueryReferencesRequestCountLWWElementReferences.newBuilder()
                        .setFromType(ContentType.OPINION.value)
                        .setValue(ByteString.copyFrom(Opinion.dislike.data))
                        .build()
                ))
                .addCountReferences(
                    Protocol.QueryReferencesRequestCountReferences.newBuilder()
                    .setFromType(ContentType.POST.value)
                    .build())
                .build()
        );

        val results = mapQueryReferences(contextUrl, response);
        val nextCursor = if (response.hasCursor()) response.cursor.toByteArray() else null
        return object : IAsyncPager<IPlatformComment>, IPager<IPlatformComment> {
            private var _results: List<IPlatformComment> = results
            private var _cursor: ByteArray? = nextCursor

            override fun hasMorePages(): Boolean {
                return _cursor != null;
            }

            override fun nextPage() {
                runBlocking { nextPageAsync() }
            }

            override suspend fun nextPageAsync() {
                val nextPageResponse = ApiMethods.getQueryReferences(PolycentricCache.SERVER, reference, _cursor,
                    Protocol.QueryReferencesRequestEvents.newBuilder()
                        .setFromType(ContentType.POST.value)
                        .addAllCountLwwElementReferences(arrayListOf(
                            Protocol.QueryReferencesRequestCountLWWElementReferences.newBuilder()
                                .setFromType(ContentType.OPINION.value)
                                .setValue(ByteString.copyFrom(Opinion.like.data))
                                .build(),
                            Protocol.QueryReferencesRequestCountLWWElementReferences.newBuilder()
                                .setFromType(ContentType.OPINION.value)
                                .setValue(ByteString.copyFrom(Opinion.dislike.data))
                                .build()
                        ))
                        .addCountReferences(
                            Protocol.QueryReferencesRequestCountReferences.newBuilder()
                                .setFromType(ContentType.POST.value)
                                .build())
                        .build()
                );

                _cursor = if (nextPageResponse.hasCursor()) nextPageResponse.cursor.toByteArray() else null
                _results = mapQueryReferences(contextUrl, nextPageResponse)
            }

            override fun getResults(): List<IPlatformComment> {
                return _results;
            }
        };
    }

    private suspend fun mapQueryReferences(contextUrl: String, response: Protocol.QueryReferencesResponse): List<IPlatformComment> {
        return response.itemsList.mapNotNull {
            val sev = SignedEvent.fromProto(it.event);
            val ev = sev.event;
            if (ev.contentType != ContentType.POST.value) {
                return@mapNotNull null;
            }

            try {
                val post = Protocol.Post.parseFrom(ev.content);
                val id = ev.system.toProto().key.toByteArray().toBase64();
                val likes = it.countsList[0];
                val dislikes = it.countsList[1];
                val replies = it.countsList[2];

                val profileEvents = ApiMethods.getQueryLatest(
                    PolycentricCache.SERVER,
                    ev.system.toProto(),
                    listOf(
                        ContentType.AVATAR.value,
                        ContentType.USERNAME.value
                    )
                ).eventsList.map { e -> SignedEvent.fromProto(e) };

                val nameEvent = profileEvents.firstOrNull { e -> e.event.contentType == ContentType.USERNAME.value };
                val avatarEvent = profileEvents.firstOrNull { e -> e.event.contentType == ContentType.AVATAR.value };
                val imageBundle = if (avatarEvent != null) {
                    val lwwElementValue = avatarEvent.event.lwwElement?.value;
                    if (lwwElementValue != null) {
                        Protocol.ImageBundle.parseFrom(lwwElementValue)
                    } else {
                        null
                    }
                } else {
                    null
                }

                val unixMilliseconds = ev.unixMilliseconds
                //TODO: Don't use single hardcoded sderver here
                val systemLinkUrl = ev.system.systemToURLInfoSystemLinkUrl(listOf(PolycentricCache.SERVER));
                val dp_25 = 25.dp(StateApp.instance.context.resources)
                return@mapNotNull PolycentricPlatformComment(
                    contextUrl = contextUrl,
                    author = PlatformAuthorLink(
                        id = PlatformID("polycentric", systemLinkUrl, null, ClaimType.POLYCENTRIC.value.toInt()),
                        name = nameEvent?.event?.lwwElement?.value?.decodeToString() ?: "Unknown",
                        url = systemLinkUrl,
                        thumbnail =  imageBundle?.selectBestImage(dp_25 * dp_25)?.let { img -> img.toURLInfoSystemLinkUrl(ev.system.toProto(), img.process, listOf(PolycentricCache.SERVER)) },
                        subscribers = null
                    ),
                    msg = if (post.content.count() > PolycentricPlatformComment.MAX_COMMENT_SIZE) post.content.substring(0, PolycentricPlatformComment.MAX_COMMENT_SIZE) else post.content,
                    rating = RatingLikeDislikes(likes, dislikes),
                    date = if (unixMilliseconds != null) Instant.ofEpochMilli(unixMilliseconds).atOffset(ZoneOffset.UTC) else OffsetDateTime.MIN,
                    replyCount = replies.toInt(),
                    reference = sev.toPointer().toReference()
                );
            } catch (e: Throwable) {
                return@mapNotNull null;
            }
        };
    }

    companion object {
        private const val TAG = "StatePolycentric";

        private var _instance: StatePolycentric? = null;
        val instance: StatePolycentric
            get(){
                if(_instance == null)
                    _instance = StatePolycentric();
                return _instance!!;
            };
    }
}