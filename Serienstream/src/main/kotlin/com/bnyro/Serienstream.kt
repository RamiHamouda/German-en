package com.bnyro

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

open class Serienstream : MainAPI() {
    override var mainUrl = "https://s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override var lang = "de"

    // ==================== Main Page ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageLists = document.select("div.carousel, section.slider").mapNotNull { ele ->
            val header = ele.selectFirst("h2, .title")?.text()?.trim() ?: return@mapNotNull null
            val items = ele.select("div.coverListItem, .slider-item").mapNotNull {
                it.toSearchResult()
            }
            HomePageList(header, items).takeIf { items.isNotEmpty() }
        }
        return newHomePageResponse(homePageLists, hasNext = false)
    }

    // ==================== Search ====================
    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            referer = "$mainUrl/search",
            headers = mapOf("x-requested-with" to "XMLHttpRequest")
        ).parsed<SearchResp>()
        
        return resp.filter {
            it.link.contains("/serie/") && !it.link.contains("episode-")
        }.map {
            newTvSeriesSearchResponse(
                it.title?.replace(Regex("</?em>"), "") ?: "No Title",
                fixUrl(it.link),
                TvType.TvSeries
            )
        }
    }

    // ==================== Load Series ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.series-title span, h1.series-title")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img, img.series-cover")?.attr("data-src"))
        val year = document.selectFirst("span[itemprop=startDate] a, .release-year")?.text()?.toIntOrNull()
        val description = document.select("p.seri_des, .series-description").text()
        val tags = document.select("div.genres li a, .genre-list a").map { it.text() }
        val actors = document.select("li:contains(Schauspieler:) ul li a, .actors-list a").map { it.text() }

        // Improved episode loading with season support
        val episodes = document.select("div#stream > ul:first-child li").mapNotNull { seasonEle ->
            val seasonLink = seasonEle.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val seasonText = seasonEle.text()
            val seasonNumber = Regex("Staffel (\\d+)").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (seasonText.contains("Staffel")) 1 else null
            
            loadSeasonEpisodes(fixUrl(seasonLink), seasonNumber)
        }.flatten()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addActors(actors)
        }
    }

    private suspend fun loadSeasonEpisodes(seasonUrl: String, season: Int?): List<Episode> {
        val document = app.get(seasonUrl).document
        return document.select("table.seasonEpisodesList tbody tr, .episode-list tr").mapNotNull { tr ->
            val episodeUrl = fixUrlNull(tr.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val episodeNumber = tr.selectFirst("meta[itemprop=episodeNumber], .episode-num")?.attr("content")?.toIntOrNull()
            val episodeTitle = tr.selectFirst(".seasonEpisodeTitle strong, .episode-title")?.text()
            
            newEpisode(episodeUrl) {
                this.season = season
                this.episode = episodeNumber
                this.name = episodeTitle
            }
        }
    }

    // ==================== Load Links ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Method 1: Standard hoster detection
        val hosters = document.select("div.hosterSiteVideo ul li, .hoster-list li").mapNotNull {
            val lang = it.attr("data-lang-key").takeIf { k -> k.isNotEmpty() } ?: "de"
            val target = it.attr("data-link-target").takeIf { t -> t.isNotEmpty() }
                ?: it.selectFirst("a[href]")?.attr("href")
            val name = it.select("h4, .hoster-name").text().takeIf { n -> n.isNotEmpty() } ?: "Unknown"
            
            if (target != null) Triple(lang, target, name) else null
        }

        // Method 2: Iframe fallback
        val iframes = document.select("iframe[src]").map {
            Pair("iframe", it.attr("src"))
        }

        // Process all found sources
        (hosters.map { (lang, target, name) -> Triple(lang, fixUrl(target), name) } +
         iframes.map { (type, src) -> Triple(type, fixUrl(src), "Embed") })
            .forEach { (type, url, name) ->
                try {
                    val redirectUrl = app.get(url, referer = data).url
                    loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                        callback(newExtractorLink(
                            name = "$name [${type.uppercase()}]",
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            type = link.type
                        ))
                    }
                } catch (e: Exception) {
                    // Try direct extraction if redirect fails
                    loadExtractor(url, data, subtitleCallback, callback)
                }
            }

        return true
    }

    // ==================== Utilities ====================
    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val title = selectFirst("h3, .item-title")?.text() ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private class SearchResp : ArrayList<SearchItem>()
    private data class SearchItem(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )
}
