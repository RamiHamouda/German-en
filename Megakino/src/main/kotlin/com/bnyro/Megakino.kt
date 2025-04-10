package com.bnyro

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class Megakino : MainAPI() {
    override var mainUrl = "https://megakino.loan"
    override var name = "Megakino"
    override val hasMainPage = true
    override var lang = "de"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Documentary)

    override val mainPage = mainPageOf(
        "" to "Trends",
        "kinofilme" to "Filme",
        "serials" to "Serien",
        "documentary" to "Dokumentationen",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page").document
        val home = document.select("#dle-content > a").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("h3").text()
        val href = fixUrl(this.attr("href"))
        val posterUrl = fixUrlNull(this.select("img").attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.quality = SearchQuality.HD
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val data =
            mapOf("do" to "search", "subaction" to "search", "story" to query.replace(" ", "+"))
        val response = app.post(mainUrl, data = data).document

        return response.select("a.poster.grid-item").map {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("div.page__subcols.d-flex h1")?.text() ?: "Unknown"
        val description = document.selectFirst("div.page__cols.d-flex p")?.text()
        val poster =
            fixUrl(document.select("div.pmovie__poster.img-fit-cover img").attr("data-src"))
        val year = document.select("div.pmovie__year > span:nth-child(2)").text().toIntOrNull()
        val genres = document.selectFirst("div.pmovie__genres")?.text()
            ?.split(" / ")?.map { it.trim() } ?: emptyList()

        val trailer = document.select("link[itemprop=embedUrl]").attr("href")

        val typeTag = document.select("div.pmovie__genres").text()
        val type = if (typeTag.contains("Filme")) TvType.Movie else TvType.TvSeries

        val streamLinks = document.select("div.pmovie__player iframe")
            .map { it.attr("src").ifEmpty { it.attr("data-src") } }.toJson()

        val related = document.select("section.pmovie__related a.poster").map {
            it.toSearchResult()
        }

        return if (type == TvType.TvSeries) {
            val episodes = document.select("select.flex-grow-1.mr-select option").map {
                val episodeNumber = it.attr("data-season").toIntOrNull()
                val episodeLink = it.select("option").attr("value")

                newEpisode(episodeLink) {
                    this.episode = episodeNumber
                    this.posterUrl = poster
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                this.recommendations = related
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, streamLinks) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                this.recommendations = related
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = parseJson<Links>(data)
        for (link in links) {
            loadExtractor(link, subtitleCallback, callback)
        }

        return links.isNotEmpty()
    }

    class Links : ArrayList<String>()
}
