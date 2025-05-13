package com.bnyro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Sto : MainAPI() {
    override var mainUrl = "https://s.to"
    override var name = "S.to"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val items = document.select(".series").map {
            val title = it.selectFirst(".title")?.text().orEmpty()
            val url = fixUrl(it.selectFirst("a")?.attr("href").orEmpty())
            val poster = fixUrl(it.selectFirst("img")?.attr("src").orEmpty())
            newAnimeSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return HomePageResponse(listOf(HomePageList("Alle Serien", items)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?query=$query"
        val document = app.get(url).document
        return document.select(".series").map {
            val title = it.selectFirst(".title")?.text().orEmpty()
            val href = fixUrl(it.selectFirst("a")?.attr("href").orEmpty())
            val poster = fixUrl(it.selectFirst("img")?.attr("src").orEmpty())
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text().orEmpty()
        val poster = document.selectFirst(".poster img")?.attr("src")?.let { fixUrl(it) }
        val episodes = document.select(".season .episode").map {
            val epUrl = fixUrl(it.selectFirst("a")?.attr("href").orEmpty())
            val epName = it.selectFirst("a")?.text().orEmpty()
            Episode(epUrl, epName)
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.reversed()) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val hosterRows = document.select(".hoster-tabs .hoster-tab")
        for (hoster in hosterRows) {
            val language = hoster.selectFirst(".language")?.text()?.trim()?.lowercase()
            if (language != null && (language.contains("englisch") || language.contains("english"))) {
                val iframe = hoster.selectFirst("iframe")?.attr("src")
                if (iframe != null) {
                    loadExtractor(iframe, data, subtitleCallback, callback)
                    return true
                }
            }
        }
        return false
    }
}
