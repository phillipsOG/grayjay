package com.futo.platformplayer.api.media.platforms.js.models

import com.caoccao.javet.values.reference.V8ValueObject
import com.futo.platformplayer.api.media.IPluginSourced
import com.futo.platformplayer.api.media.models.contents.IPlatformContent
import com.futo.platformplayer.api.media.platforms.js.SourcePluginConfig
import com.futo.platformplayer.engine.V8Plugin

class JSContentPager : JSPager<IPlatformContent>, IPluginSourced {
    override val sourceConfig: SourcePluginConfig get() = config;

    constructor(config: SourcePluginConfig, plugin: V8Plugin, pager: V8ValueObject) : super(config, plugin, pager) {}

    override fun convertResult(obj: V8ValueObject): IPlatformContent {
        return IJSContent.fromV8(config, obj);
    }
}