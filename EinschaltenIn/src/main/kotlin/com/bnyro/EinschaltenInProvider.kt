package com.bnyro

import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor


open class EinschaltenInProvider : MainAPI() {
    override var name = "EinschaltenIn"
    override var lang = "de"
    override val hasQuickSearch = true
    override val usesWebView = false
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)
    override var mainUrl = "https://einschalten.in"

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val response = app.get("${mainUrl}/__data.json")
            .parsed<Response>()

        return newHomePageResponse(
            HomePageList("Popular", toSearchResponses(response)),
            hasNext = false
        )
    }

    private fun getImageUrl(fileWithLeadingSlash: String): String {
        val file = fileWithLeadingSlash.trimStart('/')
        return "$mainUrl/image/poster/$file"
    }

    private fun getData(response: Response): List<Any>? {
        return response.nodes.firstOrNull {
            it.type == "data" && it.data.orEmpty().filterNotNull().isNotEmpty()
        }?.data?.filterNotNull()
    }

    private fun parseMovieItems(response: Response): List<MovieItem> {
        val dataItems = getData(response) ?: return emptyList()
        val infoList = dataItems.filterIsInstance<Map<String, Int>>()

        return infoList
            .filter { it.containsKey("id") && it.containsKey("title") }
            .map { infoIndices ->
                MovieItem(
                    id = dataItems[infoIndices["id"]!!] as Int,
                    title = dataItems[infoIndices["title"]!!] as String,
                    releaseDate = dataItems[infoIndices["releaseDate"]!!] as String,
                    posterPath = dataItems[infoIndices["posterPath"]!!] as String,
                    voteAverage = dataItems[infoIndices["voteAverage"]!!].toString().toFloatOrNull()
                        ?: 0f,
                    overview = infoIndices["overview"]?.let { dataItems[it] as String? },
                    runtime = infoIndices["runtime"]?.let { dataItems[it] as Int? }
                )
            }
    }

    private fun toSearchResponses(response: Response): List<SearchResponse> {
        val movieObjects = parseMovieItems(response)

        return movieObjects.map {
            newMovieSearchResponse(
                name = it.title,
                url = "$mainUrl/movies/${it.id}",
                type = TvType.Movie
            ) {
                this.posterUrl = getImageUrl(it.posterPath)
                this.year = it.releaseDate.take(4).toIntOrNull()
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("${mainUrl}/search/__data.json?query=$query")
            .parsed<Response>()

        return toSearchResponses(response)
    }

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get("$url/__data.json")
            .parsed<Response>()

        val movie = parseMovieItems(response).firstOrNull() ?: return null

        return newMovieLoadResponse(
            name = movie.title,
            url = url,
            type = TvType.Movie,
            data = url
        ) {
            this.posterUrl = getImageUrl(movie.posterPath)
            this.plot = movie.overview
            this.duration = movie.runtime
            this.year = movie.releaseDate.take(4).toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = data.split("/").last()
        val source = app.get("https://einschalten.in/api/movies/$id/watch")
            .parsed<StreamSource>()

        loadExtractor(source.streamUrl, referer = "$mainUrl/", subtitleCallback, callback)

        return true
    }

    data class MovieItem(
        val id: Int,
        val title: String,
        val releaseDate: String,
        val posterPath: String,
        val voteAverage: Float,
        val overview: String? = null,
        val runtime: Int? = null
    )

    data class Response(
        val type: String,
        val nodes: List<Node>,
    )

    data class Node(
        val type: String,
        val data: List<Any?>?,
    )

    data class StreamSource(
        val releaseName: String,
        val streamUrl: String,
    )
}