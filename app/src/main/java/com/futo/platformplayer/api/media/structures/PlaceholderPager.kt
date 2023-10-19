package com.futo.platformplayer.api.media.structures

import com.futo.platformplayer.api.media.models.contents.IPlatformContent

/**
 * A placeholder pager simply generates PlatformContent by some creator function.
 */
class PlaceholderPager : IPager<IPlatformContent> {
    val placeholderFactory: ()->IPlatformContent;
    private val _pageSize: Int;

    constructor(pageSize: Int, placeholderCreator: ()->IPlatformContent) {
        placeholderFactory = placeholderCreator;
        _pageSize = pageSize;
    }

    override fun nextPage() {};
    override fun getResults(): List<IPlatformContent> {
        val pages = ArrayList<IPlatformContent>();
        for(item in 1.._pageSize)
            pages.add(placeholderFactory());
        return pages;
    }
    override fun hasMorePages(): Boolean = true;
}