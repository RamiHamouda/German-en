package com.bnyro

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
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

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document

        val homePageLists = document.select("div.carousel").map { ele ->
            val header = ele.selectFirst("h2")?.text() ?: return@map null

            val items = ele.select("div.coverListItem").mapNotNull {
                it.toSearchResult()
            }
            HomePageList(header, items).takeIf { items.isNotEmpty() }
        }.filterNotNull()

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val resp = app.post(
            "$mainUrl/ajax/search",
            data = mapOf("keyword" to query),
            referer = "$mainUrl/search",
            headers = mapOf("x-requested-with" to "XMLHttpRequest")
        )
        return resp.parsed<SearchResp>().filter {
            !it.link.contains("episode-") && it.link.contains("/stream")
        }.map {
            newTvSeriesSearchResponse(
                it.title?.replace(Regex("</?em>"), "") ?: "",
                fixUrl(it.link),
                TvType.TvSeries
            )
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("div.series-title span")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
        val tags = document.select("div.genres li a").map { it.text() }
        val year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
        val description = document.select("p.seri_des").text()
        val actors = document.select("li:contains(Schauspieler:) ul li a")
            .map { it.select("span").text() }

        val episodes = document.select("div#stream > ul:first-child li").mapNotNull { ele ->
            val seasonLink = ele.selectFirst("a") ?: return@mapNotNull null
            val seasonNumber = seasonLink.text().toIntOrNull()
            val seasonDocument = app.get(fixUrl(seasonLink.attr("href"))).document

            seasonDocument.select("table.seasonEpisodesList tbody tr").map { eps ->
                newEpisode(fixUrl(eps.selectFirst("a")?.attr("href") ?: return@map null)) {
                    this.episode = eps.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
                    this.name = eps.selectFirst(".seasonEpisodeTitle")?.text()
                    this.season = seasonNumber
                }
            }.filterNotNull()
        }.flatten()

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.name = title
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Select all hosters
        val allHosters = document.select("div.hosterSiteVideo ul li")

        // Check if no hosters found
        if (allHosters.isEmpty()) {
            println("No hosters found on this page.")
            return false
        }

        // DEBUG: Print all hosters and their details
        allHosters.forEachIndexed { index, host ->
            val hostName = host.select("h4").text()
            val flagSrc = host.selectFirst("img.flag")?.attr("src") ?: "NO FLAG"
            println("Hoster [$index]: Host: $hostName | Flag: $flagSrc")
        }

        // Loop through all hosters to get their links
        allHosters.forEach { hoster ->
            val targetUrl = hoster.attr("data-link-target") // Extract the link from `data-link-target`
            val hostName = hoster.select("h4").text() // Host name
            val flagLabel = hoster.selectFirst("img.flag")?.attr("title") ?: "Unknown"
            val name = "$hostName [$flagLabel]"

            // DEBUG: Log the hoster's target URL
            println("Found Hoster: $hostName | Link Target: $targetUrl")

            // Optional: Filter hosters based on the flag (e.g., English or US)
            if (flagLabel.contains("Englisch", ignoreCase = true)) {
                // Follow the target URL and fetch the final link
                val redirectUrl = app.get(fixUrl(targetUrl)).url

                // Use the extractor to handle the final video link
                loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                    val linkWithFixedName = runBlocking {
                        newExtractorLink(
                            source = hostName,
                            name = name,
                            url = link.url
                        ) {
                            referer = link.referer
                            quality = link.quality
                            type = link.type
                            headers = link.headers
                            extractorData = link.extractorData
                        }
                    }
                    // Invoke callback for each valid link
                    callback.invoke(linkWithFixedName)
                }
            } else {
                // Optional: Log or process hosters that don't match the language filter
                println("Skipped Hoster: $hostName (not English)")
            }
        }

        return true
    }

    private fun Element.toSearchResult(): TvSeriesSearchResponse? {
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    private class SearchResp : ArrayList<SearchItem>()

    private data class SearchItem(
        @JsonProperty("link") val link: String,
        @JsonProperty("title") val title: String? = null,
    )
}
