package com.bnyro



import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Document

class Serienstream : MainAPI() {

    override var mainUrl = "https://www.s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)

    override var lang = "en"

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url).document ?: throw Exception("Document not found")
            val title = document.selectFirst("div.series-title span")?.text() ?: return null
            val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
            val tags = document.select("div.genres li a").map { it.text() }
            val year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
            val description = document.select("p.seri_des").text()

            val actors = document.select("li:contains(Schauspieler:) ul li a span")
                .map { ActorData(name = it.text()) }

            val episodes = fetchEpisodes(document)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.name = title
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.actors = actors
            }
        } catch (e: Exception) {
            println("Error loading series: ${e.message}")
            null
        }
    }

    private suspend fun fetchEpisodes(document: Document): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val seasonLinks = document.select("div#stream > ul:first-child li a")

        for (seasonLink in seasonLinks) {
            val seasonNumber = seasonLink.text().toIntOrNull() ?: continue
            val seasonUrl = fixUrl(seasonLink.attr("href"))
            val seasonDoc = app.get(seasonUrl).document ?: continue

            val rows = seasonDoc.select("table.seasonEpisodesList tbody tr")
            for (row in rows) {
                val epUrl = fixUrlNull(row.selectFirst("a")?.attr("href")) ?: continue

                val episode = newEpisode(epUrl) {
                    episode = row.selectFirst("meta[itemprop=episodeNumber]")?.attr("content")?.toIntOrNull()
                    name = row.selectFirst(".seasonEpisodeTitle")?.text()
                    season = seasonNumber
                }

                episodes.add(episode)
            }
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val document = app.get(data).document ?: throw Exception("Document not found")

            // Select video links, filtering for only English links
            val links = document.select("div.hosterSiteVideo ul li")
                .map {
                    Triple(
                        it.attr("data-lang-key"),
                        it.attr("data-link-target"),
                        it.select("h4").text()
                    )
                }
                .filter { (langKey, _, _) -> langKey == "en" } // Ensure only English links are processed

            // Process each English link
            links.forEach { (langKey, target, label) ->
                val redirectUrl = app.get(fixUrl(target)).url
                val lang = langKey.getLanguage(document)
                val name = "$label [$lang]"

                CoroutineScope(Dispatchers.IO).launch {
                    loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                        callback.invoke(
                            newExtractorLink(
                                source = label,
                                name = name,
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
            true
        } catch (e: Exception) {
            println("Error loading links: ${e.message}")
            false
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url" else url
    }

    private fun String.getLanguage(document: Document): String {
        return "English"
    }
}
