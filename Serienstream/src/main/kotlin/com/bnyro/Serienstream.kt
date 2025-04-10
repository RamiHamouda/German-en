package com.bnyro

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

open class Serienstream : MainAPI() {
    override var mainUrl = "https://s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val hasMainPage = true
    override var lang = "en"

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
            headers = mapOf(
                "x-requested-with" to "XMLHttpRequest"
            )
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
        val actors =
            document.select("li:contains(Schauspieler:) ul li a").map { it.select("span").text() }

        val episodes = document.select("div#stream > ul:first-child li").mapNotNull { ele ->
            val seasonLink = ele.selectFirst("a") ?: return@mapNotNull null
            val seasonNumber = seasonLink.text().toIntOrNull()
            val seasonDocument = app.get(fixUrl(seasonLink.attr("href"))).document

            seasonDocument.select("table.seasonEpisodesList tbody tr").map { eps ->
                newEpisode(
                    fixUrl(eps.selectFirst("a")?.attr("href") ?: return@map null),
                ) {
                    this.episode = eps.selectFirst("meta[itemprop=episodeNumber]")
                        ?.attr("content")?.toIntOrNull()
                    this.name = eps.selectFirst(".seasonEpisodeTitle")?.text()
                    this.season = seasonNumber
                }
            }.filterNotNull()
        }.flatten()

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes
        ) {
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

        // Get all hosters
        val allHosters = document.select("div.hosterSiteVideo ul li")

        // Check if we have at least two hosters
        if (allHosters.size < 2) {
            println("Not enough hosters found.")
            return false
        }

        // Select the second hoster (index 1)
        val secondHoster = allHosters[1] // Select the second item

        val targetUrl = secondHoster.attr("data-link-target") // Extract the link
        val hostName = secondHoster.select("h4").text() // Host name

        // Debug output for the second hoster
        println("Selected second hoster: $hostName | Target URL: $targetUrl")

        // If the URL is empty, return false
        if (targetUrl.isEmpty()) {
            println("No target URL found for the second hoster.")
            return false
        }

        // Proceed with processing the link
        val fixedUrl = fixUrl(targetUrl) // Fix the URL if necessary

        // Debug output for fixed URL
        println("Fixed URL: $fixedUrl")

        // Follow the link and fetch the final URL
        val redirectUrl = app.get(fixedUrl).url

        // Now, handle the extractor to get the video link
        loadExtractor(redirectUrl, data, subtitleCallback) { link ->
            val linkWithFixedName = runBlocking {
                newExtractorLink(
                    source = hostName,
                    name = hostName,
                    url = link.url
                ) {
                    referer = link.referer
                    quality = link.quality
                    type = link.type
                    headers = link.headers
                    extractorData = link.extractorData
                }
            }
            callback.invoke(linkWithFixedName)
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
