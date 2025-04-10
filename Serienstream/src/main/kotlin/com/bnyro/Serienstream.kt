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
    override val instantLinkLoading = false // Important for debugging

    // ==================== DEBUGGING UTILS ====================
    private fun debugLog(message: String) {
        println("[$name DEBUG] $message") // Remove in production
    }

    // ==================== MAIN PAGE ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl, timeout = 60).document
        debugLog("Main page loaded: ${document.title()}")

        val homePageLists = document.select("div.carousel, section.series-slider").mapNotNull { ele ->
            val header = ele.selectFirst("h2, .slider-header")?.text()?.trim() ?: return@mapNotNull null
            val items = ele.select("div.coverListItem, .slider-item").mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) HomePageList(header, items) else null
        }

        debugLog("Found ${homePageLists.size} carousels")
        return newHomePageResponse(homePageLists, hasNext = false)
    }

    // ==================== SEARCH ====================
    override suspend fun search(query: String): List<SearchResponse> {
        debugLog("Searching for: $query")
        val resp = try {
            app.post(
                "$mainUrl/ajax/search",
                data = mapOf("keyword" to query),
                referer = "$mainUrl/search",
                headers = mapOf("x-requested-with" to "XMLHttpRequest"),
                timeout = 60
            ).parsed<SearchResp>()
        } catch (e: Exception) {
            debugLog("Search failed: ${e.message}")
            return emptyList()
        }

        return resp.filter {
            it.link.contains("/stream") && !it.link.contains("episode-")
        }.mapNotNull {
            val title = it.title?.replace(Regex("</?em>"), "") ?: "No Title"
            val url = fixUrl(it.link)
            debugLog("Found result: $title - $url")
            newTvSeriesSearchResponse(title, url, TvType.TvSeries)
        }
    }

    // ==================== LOAD SERIES ====================
    override suspend fun load(url: String): LoadResponse? {
        debugLog("Loading series: $url")
        val document = try {
            app.get(url, timeout = 60).document
        } catch (e: Exception) {
            debugLog("Failed to load series: ${e.message}")
            return null
        }

        val title = document.selectFirst("div.series-title span, h1.series-title")?.text() 
            ?: return null.also { debugLog("No title found") }
        
        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            loadEpisodes(document, url)
        ) {
            this.posterUrl = fixUrlNull(document.selectFirst("div.seriesCoverBox img, img.series-cover")?.attr("data-src"))
            this.year = document.selectFirst("span[itemprop=startDate] a, .release-year")?.text()?.toIntOrNull()
            this.plot = document.select("p.seri_des, .series-description").text()
            this.tags = document.select("div.genres li a, .genre-list a").map { it.text() }
            addActors(document.select("li:contains(Schauspieler:) ul li a, .actors-list a").map { it.text() })
        }.also { debugLog("Loaded series: $title with ${it.episodes?.size} episodes") }
    }

    private suspend fun loadEpisodes(document: Document, baseUrl: String): List<Episode> {
        return document.select("div#stream > ul li, .season-list li").mapNotNull { seasonEle ->
            val seasonLink = seasonEle.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val seasonText = seasonEle.text()
            val seasonNumber = Regex("(\\d+)").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                ?: if (seasonText.contains("Staffel")) 1 else null
            
            parseSeasonEpisodes(fixUrl(seasonLink), seasonNumber, baseUrl)
        }.flatten()
    }

    private suspend fun parseSeasonEpisodes(seasonUrl: String, season: Int?, baseUrl: String): List<Episode> {
        debugLog("Loading season: $seasonUrl")
        return try {
            val seasonDoc = app.get(seasonUrl, referer = baseUrl, timeout = 60).document
            seasonDoc.select("table.seasonEpisodesList tbody tr, .episode-list tr").mapNotNull { eps ->
                val epUrl = eps.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                val epNum = eps.selectFirst("meta[itemprop=episodeNumber], .episode-number")?.attr("content")?.toIntOrNull()
                val epName = eps.selectFirst(".seasonEpisodeTitle, .episode-title")?.text()
                
                newEpisode(fixUrl(epUrl)) {
                    this.season = season
                    this.episode = epNum
                    this.name = epName
                }.also { debugLog("Found episode: S${season}E${epNum} - ${epName}") }
            }
        } catch (e: Exception) {
            debugLog("Failed to load season: ${e.message}")
            emptyList()
        }
    }

    // ==================== LOAD LINKS ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("Loading links for: $data")
        val document = try {
            app.get(data, timeout = 60).document
        } catch (e: Exception) {
            debugLog("Failed to load episode: ${e.message}")
            return false
        }

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

        // Method 3: Direct video fallback
        val videos = document.select("video source[src]").map {
            Pair("direct", it.attr("src"))
        }

        debugLog("""
            Found:
            - ${hosters.size} hosters
            - ${iframes.size} iframes
            - ${videos.size} direct videos
        """.trimIndent())

        // Process all found sources
        (hosters.map { (lang, target, name) -> Triple(lang, fixUrl(target), name) } +
         iframes.map { (type, src) -> Triple(type, fixUrl(src), "Embed") } +
         videos.map { (type, src) -> Triple(type, fixUrl(src), "Direct") })
            .forEach { (type, url, name) ->
                debugLog("Processing source: $name ($type) - $url")
                try {
                    val redirectUrl = app.get(url, referer = data, timeout = 60).url
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
                    debugLog("Failed to process $url: ${e.message}")
                }
            }

        return true
    }

    // ==================== UTILITIES ====================
    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val title = selectFirst("h3, .item-title")?.text() ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }.also { debugLog("Search result: $title - $href") }
    }

    private class SearchResp : ArrayList<SearchItem>()
    private data class SearchItem(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )
}
