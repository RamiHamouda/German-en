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
    override val instantLinkLoading = false

    // Debugging utility
    private fun debugLog(message: String) {
        println("[$name DEBUG] $message")
    }

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

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            app.post(
                "$mainUrl/ajax/search",
                data = mapOf("keyword" to query),
                headers = mapOf(
                    "x-requested-with" to "XMLHttpRequest",
                    "referer" to "$mainUrl/search"
                )
            ).parsed<SearchResp>().mapNotNull {
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

    override suspend fun load(url: String): LoadResponse? {
        val document = try {
            app.get(url).document.also {
                debugLog("Loaded series page: ${it.title()}")
            }
        } catch (e: Exception) {
            debugLog("Failed to load series: ${e.message}")
            return null
        }

        val title = document.selectFirst("div.series-title span, h1.series-title")?.text() ?: run {
            debugLog("No title found")
            return null
        }
        
        val episodes = document.select("div#stream > ul:first-child li").mapNotNull { seasonEle ->
            val seasonLink = seasonEle.selectFirst("a")?.attr("href") ?: return@mapNotNull null
            val seasonNumber = seasonEle.text().let { text ->
                Regex("Staffel (\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (text.contains("Staffel")) 1 else null
            }
            loadSeasonEpisodes(fixUrl(seasonLink), seasonNumber, url)
        }.flatten()

        debugLog("Found ${episodes.size} episodes for $title")

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
            this.posterUrl = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
            this.year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
            this.plot = document.select("p.seri_des").text()
            this.tags = document.select("div.genres li a").map { it.text() }
            addActors(document.select("li:contains(Schauspieler:) ul li a").map { it.select("span").text() })
        }
    }

    private suspend fun loadSeasonEpisodes(seasonUrl: String, season: Int?, referer: String): List<Episode> {
        return try {
            val doc = app.get(seasonUrl, referer = referer).document
            doc.select("table.seasonEpisodesList tbody tr").mapNotNull { tr ->
                val epUrl = fixUrlNull(tr.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
                val epNum = tr.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
                val epTitle = tr.selectFirst(".seasonEpisodeTitle strong")?.text()
                
                newEpisode(epUrl) {
                    this.season = season
                    this.episode = epNum
                    this.name = epTitle
                }.also {
                    debugLog("Found episode: S${season}E${epNum} - ${epTitle}")
                }
            }
        } catch (e: Exception) {
            debugLog("Failed to load season: ${e.message}")
            emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debugLog("Attempting to load links for: $data")
        
        val document = try {
            app.get(data).document.also {
                debugLog("Page title: ${it.title()}")
            }
        } catch (e: Exception) {
            debugLog("Failed to load episode page: ${e.message}")
            return false
        }

        // Method 1: Standard hosters
        val hosters = document.select("div.hosterSiteVideo ul li").mapNotNull {
            val lang = it.attr("data-lang-key").ifEmpty { "de" }
            val target = it.attr("data-link-target").ifEmpty { 
                it.selectFirst("a[href]")?.attr("href") 
            } ?: return@mapNotNull null
            val name = it.select("h4").text().ifEmpty { "Unknown" }
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

        // Method 3: Direct video elements
        val videos = document.select("video source[src]").map {
            Pair("direct", fixUrl(it.attr("src")))
        }.also {
            debugLog("Found ${it.size} direct video sources")
        }

        // Process all found sources
        (hosters + iframes + videos).forEach { (type, url, name) ->
            try {
                debugLog("Processing $type source: $url")
                val redirectUrl = app.get(url, referer = data).url
                debugLog("Redirected to: $redirectUrl")
                
                loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                    callback(newExtractorLink(
                        name = "$name [${type.uppercase()}]",
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        type = link.type
                    ).also {
                        debugLog("Successfully created extractor link: ${it.name}")
                    })
                }
            } catch (e: Exception) {
                debugLog("Failed to process $url: ${e.message}")
                // Try direct extraction as fallback
                loadExtractor(url, data, subtitleCallback, callback)
            }
        }

        val totalLinks = hosters.size + iframes.size + videos.size
        debugLog("Total links processed: $totalLinks")
        return totalLinks > 0
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val href = fixUrlNull(selectFirst("a")?.attr("href")) ?: return null
        val title = selectFirst("h3")?.text() ?: return null
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
