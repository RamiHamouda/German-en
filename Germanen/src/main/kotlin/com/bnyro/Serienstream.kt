package com.bnyro

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Document

class Serienstream : MainAPI() {

    override var mainUrl = "https://www.s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)

    override var lang = "en" // Change val to var for lang property

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // Extract basic series information
        val title = document.selectFirst("div.series-title span")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
        val tags = document.select("div.genres li a").map { it.text() }
        val year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
        val description = document.select("p.seri_des").text()

        // Fetch actors from the series page
        val actors = document.select("li:contains(Schauspieler:) ul li a span")
            .map { ActorData(name = it.text()) } // Convert Actor to ActorData

        // Extract episode information
        val episodes = mutableListOf<Episode>()
        val seasonLinks = document.select("div#stream > ul:first-child li a")

        // Process each season's episodes
        for (seasonLink in seasonLinks) {
            val seasonNumber = seasonLink.text().toIntOrNull() ?: continue
            val seasonUrl = fixUrl(seasonLink.attr("href"))
            val seasonDoc = app.get(seasonUrl).document

            val rows = seasonDoc.select("table.seasonEpisodesList tbody tr")
            for (row in rows) {
                val epUrl = fixUrlNull(row.selectFirst("a")?.attr("href")) ?: continue

                // Create a new episode object
                val episode = newEpisode(epUrl) {
                    episode = row.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
                    name = row.selectFirst(".seasonEpisodeTitle")?.text()
                    season = seasonNumber
                }

                episodes.add(episode)
            }
        }

        // Return the series data
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.name = title
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val links = document.select("div.hosterSiteVideo ul li")
            .map {
                Triple(
                    it.attr("data-lang-key"),
                    it.attr("data-link-target"),
                    it.select("h4").text()
                )
            }
            .filter { (langKey, _, _) -> langKey == "en" } // Filter only English links

        links.forEach { (langKey, target, label) ->
            val redirectUrl = app.get(fixUrl(target)).url
            val lang = langKey.getLanguage(document)
            val name = "$label [$lang]"

            // Launch coroutine for extracting links
            CoroutineScope(Dispatchers.IO).launch {
                loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                    callback.invoke(
                        newExtractorLink(
                            source = label,
                            name = name,  // Ensure name is passed correctly
                            url = link.url
                        ) {
                            referer = link.referer
                            quality = link.quality
                            type = link.type
                            headers = link.headers
                            extractorData = link.extractorData
                        }
                    )
                }
            }
        }

        return true
    }

    // Helper function to fix null URLs
    private fun fixUrlNull(url: String?): String? {
        return url?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    // Helper function to fix URLs
    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url" else url
    }

    // Helper function to load subtitles if needed
    private suspend fun loadExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Implement the logic to fetch subtitle and video links here.
    }

    // Helper function to get the language from the link's key
    private fun String.getLanguage(document: Document): String {
        // Implement logic to extract the language based on the document
        return "English"
    }
}
