package com.futo.platformplayer.api.media.platforms.js.models.sources

import com.caoccao.javet.values.reference.V8ValueObject
import com.futo.platformplayer.api.media.models.streams.sources.IDashManifestSource
import com.futo.platformplayer.api.media.models.streams.sources.IVideoUrlSource
import com.futo.platformplayer.engine.IV8PluginConfig
import com.futo.platformplayer.getOrNull
import com.futo.platformplayer.getOrThrow

class JSDashManifestSource : IVideoUrlSource, IDashManifestSource, JSSource {
    override val width : Int = 0;
    override val height : Int = 0;
    override val container : String = "application/dash+xml";
    override val codec : String = "Dash";
    override val name : String;
    override val bitrate: Int? = null;
    override val url : String;
    override val duration: Long;

    override var priority: Boolean = false;

    constructor(config: IV8PluginConfig, obj: V8ValueObject) : super(TYPE_DASH, config, obj) {
        val contextName = "DashSource";

        name = _obj.getOrThrow(config, "name", contextName);
        url = _obj.getOrThrow(config, "url", contextName);
        duration = _obj.getOrThrow(config, "duration", contextName);

        priority = obj.getOrNull(config, "priority", contextName) ?: false;
    }

    override fun getVideoUrl(): String {
        return url;
    }
}