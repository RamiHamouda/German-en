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
        val allHosters = document.select("div.hosterSiteVideo ul li")

        // DEBUG: Print all hosters and their flag src for inspection
        allHosters.forEachIndexed { index, host ->
            val hostName = host.select("h4").text()
            val flagSrc = host.selectFirst("img.flag")?.attr("src") ?: "NO FLAG"
            println("[$index] Host: $hostName | Flag: $flagSrc")
        }

        // Filter for English language hosters by checking 'english' in the flag filename
        val englishHosters = allHosters.filter {
            val flagSrc = it.selectFirst("img.flag")?.attr("src") ?: return@filter false
            flagSrc.contains("english", ignoreCase = true)
        }

        println("Found ${englishHosters.size} English hosters.")

        val selectedHoster = if (englishHosters.size >= 2) {
            englishHosters[1]
        } else {
            println("Not enough English hosters, using first available.")
            allHosters.firstOrNull() ?: return false
        }

        val targetUrl = selectedHoster.attr("data-link-target")
        val hostName = selectedHoster.select("h4").text()
        val flagLabel = selectedHoster.selectFirst("img.flag")?.attr("title") ?: "Unknown"
        val name = "$hostName [$flagLabel]"

        val redirectUrl = app.get(fixUrl(targetUrl)).url

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
