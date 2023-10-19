package com.futo.platformplayer.engine

interface IV8PluginConfig {
    val name: String;
    val allowEval: Boolean;
    val allowUrls: List<String>;
    val packages: List<String>;
}

@kotlinx.serialization.Serializable
class V8PluginConfig : IV8PluginConfig {
    override val name: String;
    override val allowEval: Boolean;
    override val allowUrls: List<String>;
    override val packages: List<String>;

    constructor() {
        name = "Unknown";
        allowEval = false;
        allowUrls = listOf();
        packages = listOf();
    }
    constructor(name: String, allowEval: Boolean, allowUrls: List<String>, packages: List<String> = listOf()) {
        this.name = name;
        this.allowEval = allowEval;
        this.allowUrls = allowUrls;
        this.packages = packages;
    }
}