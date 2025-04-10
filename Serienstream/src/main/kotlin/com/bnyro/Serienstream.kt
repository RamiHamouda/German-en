package com.bnyro

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

open class Serienstream : MainAPI() {
    override var mainUrl = "https://s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override var lang = "de"

    // ============================ Main ============================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageLists = document.select("div.carousel").mapNotNull { ele ->
            val header = ele.selectFirst("h2")?.text() ?: return@mapNotNull null
            val items = ele.select("div.coverListItem").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) HomePageList(header, items) else null
        }
        return newHomePageResponse(homePageLists, hasNext = false)
    }

    // ============================ Search ============================
    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            referer = "$mainUrl/search",
            headers = mapOf("x-requested-with" to "XMLHttpRequest")
        ).parsed<SearchResp>()
        
        return resp.filter {
            it.link.contains("/stream") && !it.link.contains("episode-")
        }.map {
            newTvSeriesSearchResponse(
                it.title?.replace(Regex("</?em>"), "") ?: "No Title",
                fixUrl(it.link),
                TvType.TvSeries
            )
        }
    }

    // ============================ Load ============================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val title = document.selectFirst("div.series-title span")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
        val year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
        
        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            loadEpisodes(document)
        ) {
            this.posterUrl = poster
            this.year = year
            this.plot = document.select("p.seri_des").text()
            this.tags = document.select("div.genres li a").map { it.text() }
            addActors(document.select("li:contains(Schauspieler:) ul li a").map { it.select("span").text() })
        }
    }

    private suspend fun loadEpisodes(document: Document): List<Episode> {
        return document.select("div#stream > ul:first-child li").mapNotNull { seasonEle ->
            val seasonLink = seasonEle.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val seasonNumber = seasonEle.text().toIntOrNull()
            parseSeasonEpisodes(fixUrl(seasonLink), seasonNumber)
        }.flatten()
    }

    private suspend fun parseSeasonEpisodes(seasonUrl: String, season: Int?): List<Episode> {
        return app.get(seasonUrl).document.select("table.seasonEpisodesList tbody tr").mapNotNull { eps ->
            val epUrl = eps.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            newEpisode(fixUrl(epUrl)) {
                this.season = season
                this.episode = eps.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
                this.name = eps.selectFirst(".seasonEpisodeTitle")?.text()
            }
        }
    }

    // ============================ Links ============================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Try both container types
        val hosters = document.select("div.hosterSiteVideo ul li, div.hoster-list ul li").mapNotNull { 
            Triple(
                it.attr("data-lang-key").takeIf { key -> key.isNotEmpty() } ?: "de",
                it.attr("data-link-target").takeIf { t -> t.isNotEmpty() } 
                    ?: it.selectFirst("a[href]")?.attr("href"),
                it.select("h4, span.hoster-name").text()
            ).takeIf { triple -> triple.second != null }
        }

        if (hosters.isEmpty()) {
            // Fallback to direct video detection
            document.select("iframe[src], video source[src]").forEach { 
                loadExtractor(fixUrl(it.attr("src")), data, subtitleCallback, callback)
            }
            return true
        }

        hosters.forEach { (langKey, target, name) ->
            val redirectUrl = app.get(fixUrl(target!!)).url
            loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                callback(newExtractorLink(
                    name = "$name [${langKey.uppercase()}]",
                    url = link.url,
                    referer = link.referer,
                    quality = link.quality,
                    type = link.type
                ))
            }
        }
        
        return true
    }

    // ============================ Utilities ============================
    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        return newTvSeriesSearchResponse(
            selectFirst("h3")?.text() ?: return null,
            href,
            TvType.TvSeries
        ) {
            posterUrl = fixUrlNull(selectFirst("img")?.attr("data-src"))
        }
    }

    private class SearchResp : ArrayList<SearchItem>()
    private data class SearchItem(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )
}
