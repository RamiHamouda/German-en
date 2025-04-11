package com.bnyro

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class Serienstream : MainAPI() {
    override var mainUrl = "https://s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override val hasMainPage = true
    override var lang = "de"
    override val instantLinkLoading = false

    // ==================== Debugging ====================
    private fun debugLog(message: String) {
        println("[$name DEBUG] $message")
    }

    // ==================== Main Page ====================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(mainUrl).document
        val homePageLists = document.select("div.carousel, section.slider").mapNotNull { slider ->
            val title = slider.selectFirst("h2, .slider-title")?.text()?.trim() ?: return@mapNotNull null
            val items = slider.select("div.coverListItem, .slider-item").mapNotNull {
                it.toSearchResult()
            }
            HomePageList(title, items).takeIf { items.isNotEmpty() }
        }
        return newHomePageResponse(homePageLists)
    }

    // ==================== Search ====================
    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            app.post(
                "$mainUrl/ajax/search",
                data = mapOf("keyword" to query),
                headers = mapOf(
                    "x-requested-with" to "XMLHttpRequest",
                    "referer" to "$mainUrl/search"
                )
            ).parsed<SearchResponse>().mapNotNull {
                if (!it.link.contains("/serie/")) return@mapNotNull null
                newTvSeriesSearchResponse(
                    it.title?.replace(Regex("</?em>"), "") ?: "No Title",
                    fixUrl(it.link),
                    TvType.TvSeries
                )
            }
        } catch (e: Exception) {
            debugLog("Search failed: ${e.message}")
            emptyList()
        }
    }

    // ==================== Load Series ====================
    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url).document.also {
                debugLog("Loaded series page: ${it.title()}")
            }
        } catch (e: Exception) {
            debugLog("Failed to load series: ${e.message}")
            return null
        }

        val title = document.selectFirst(".series-title, h1")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst(".seriesCoverBox img, .poster")?.attr("src"))
        val year = document.selectFirst("[itemprop=startDate], .year")?.text()?.toIntOrNull()
        val description = document.selectFirst(".seri_des, .description")?.text()
        val tags = document.select(".genres a, .genre-tag").map { it.text() }
        val actors = document.select(".actors a, [itemprop=actor]").map { it.text() }

        val episodes = document.select(".season-list li, #stream li").mapNotNull { seasonEle ->
            val seasonLink = seasonEle.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val seasonNumber = seasonEle.text().let { text ->
                Regex("Staffel (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (text.contains("Staffel")) 1 else null
            }
            loadSeasonEpisodes(fixUrl(seasonLink), seasonNumber, url)
        }.flatten()

        debugLog("Loaded ${episodes.size} episodes for $title")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addActors(actors)
        }
    }

    private suspend fun loadSeasonEpisodes(seasonUrl: String, season: Int?, referer: String): List<Episode> {
        return try {
            val doc = app.get(seasonUrl, referer = referer).document
            doc.select(".episode-list tr, table tbody tr").mapNotNull { tr ->
                val epUrl = fixUrlNull(tr.selectFirst("a[href]")?.attr("href")) ?: return@mapNotNull null
                val epNum = tr.selectFirst("[itemprop=episodeNumber], .episode-num")?.text()?.toIntOrNull()
                val epTitle = tr.selectFirst(".episode-title, [itemprop=name]")?.text()
                
                newEpisode(epUrl) {
                    this.season = season
                    this.episode = epNum
                    this.name = epTitle
                }.also {
                    debugLog("Found episode: S${season}E${epNum} - $epTitle")
                }
            }
        } catch (e: Exception) {
            debugLog("Failed to load season: ${e.message}")
            emptyList()
        }
    }

    // ==================== Load Links ====================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("Loading links for: $data")
        
        val document = try {
            app.get(data).document.also {
                debugLog("Loaded episode page: ${it.title()}")
            }
        } catch (e: Exception) {
            debugLog("Failed to load episode page: ${e.message}")
            return false
        }

        // Method 1: Standard hosters
        val hosters = document.select(".hoster-list li, [data-link-target]").mapNotNull {
            val lang = it.attr("data-lang-key").ifEmpty { "de" }
            val target = it.attr("data-link-target").ifEmpty { 
                it.selectFirst("a[href]")?.attr("href") 
            } ?: return@mapNotNull null
            val name = it.select("h4, .hoster-name").text().ifEmpty { "Unknown" }
            Triple(lang, fixUrl(target), name)
        }.also {
            debugLog("Found ${it.size} standard hosters")
        }

        // Method 2: Iframe fallback
        val iframes = document.select("iframe[src]").map {
            Pair("iframe", fixUrl(it.attr("src")))
        }.also {
            debugLog("Found ${it.size} iframes")
        }

        // Method 3: Direct video sources
        val videos = document.select("video source[src], .video-container source[src]").map {
            Pair("direct", fixUrl(it.attr("src")))
        }.also {
            debugLog("Found ${it.size} direct video sources")
        }

        // Method 4: JavaScript player detection
        val jsPlayers = document.select("script:containsData(player), script:containsData(source)").mapNotNull {
            Regex("""(https?://[^"'\s]+\.(mp4|m3u8))""").find(it.data())?.groupValues?.get(1)
        }.map {
            Pair("js-player", fixUrl(it))
        }.also {
            debugLog("Found ${it.size} JS players")
        }

        // Process all sources
        (hosters + iframes + videos + jsPlayers).forEach { (type, url, name) ->
            try {
                debugLog("Processing $type source: $url")
                val finalUrl = if (type == "direct" || type == "js-player") {
                    url
                } else {
                    app.get(url, referer = data).url
                }
                
                loadExtractor(finalUrl, data, subtitleCallback) { link ->
                    callback(newExtractorLink(
                        name = "$name [${type.uppercase()}]",
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        type = link.type
                    ).also {
                        debugLog("Created extractor link: ${it.name}")
                    })
                }
            } catch (e: Exception) {
                debugLog("Failed to process $url: ${e.message}")
            }
        }

        val totalLinks = hosters.size + iframes.size + videos.size + jsPlayers.size
        debugLog("Total links processed: $totalLinks")
        return totalLinks > 0
    }

    // ==================== Utilities ====================
    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val title = selectFirst("h3, .title")?.text() ?: return null
        val poster = fixUrlNull(selectFirst("img")?.attr("data-src") ?: selectFirst("img")?.attr("src"))
        
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private class SearchResponse : ArrayList<SearchItem>()
    private data class SearchItem(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )
}
