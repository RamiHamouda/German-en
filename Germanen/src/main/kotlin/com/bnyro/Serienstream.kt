package com.bnyro.Serienstream

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.extractor.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document

class Serienstream : MainAPI() {
    override var mainUrl = "https://www.s.to"
    override var name = "Serienstream"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "en"

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url).document ?: return null
            val title = document.selectFirst("div.series-title span")?.text() ?: return null
            val poster = fixUrlNull(document.selectFirst("div.seriesCoverBox img")?.attr("data-src"))
            val tags = document.select("div.genres li a").map { it.text() }
            val year = document.selectFirst("span[itemprop=startDate] a")?.text()?.toIntOrNull()
            val description = document.select("p.seri_des").text()

            val actors = document.select("li:contains(Schauspieler:) ul li a span")
                .map { actorName -> ActorData(Actor(actorName.text(), null)) }

            val episodes = fetchEpisodes(document)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.actors = actors
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            val document = app.get(data).document ?: return false

            val links = document.select("div.hosterSiteVideo ul li")
                .mapNotNull {
                    val langKey = it.attr("data-lang-key")
                    val target = it.attr("data-link-target")
                    val label = it.select("h4").text()
                    if (langKey == "en") Triple(langKey, target, label) else null
                }

            for ((_, target, label) in links) {
                val redirectResponse = app.get(fixUrl(target))
                val redirectUrl = redirectResponse.url
                val name = "$label [English]"

                loadExtractor(redirectUrl, data, subtitleCallback) { link ->
                    callback(
                        ExtractorLink(
                            source = label,
                            name = name,
                            url = link.url,
                            referer = link.referer,
                            quality = link.quality,
                            type = link.type?.name, // Convert Enum to String
                            headers = link.headers
                        )
                    )
                }
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun fixUrlNull(url: String?): String? {
        return url?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url" else url
    }
}
