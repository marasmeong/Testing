package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async

class Idlix : MainAPI() {
    private val posterCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val posterSemaphore = kotlinx.coroutines.sync.Semaphore(8)


    override var mainUrl = "https://z2.idlixku.com"
    override var name = "IDLIX"
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "Trending Now" to "Lagi Tren Sekarang",
        "Recently Added Movies" to "Film Terbaru",
        "Recently Added Series" to "Series Terbaru"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(mainUrl).document

        val heading = document.select("h2").firstOrNull {
            it.text().trim() == request.data
        }

        val items = kotlinx.coroutines.coroutineScope {
            heading?.parent()
                ?.select("a[href^=/movie/], a[href^=/series/]")
                ?.map { element ->
                    async {
                        posterSemaphore.withPermit {
                            val title = element.text().trim()
                            val href = element.attr("href")

                            if (title.isBlank() || href.isBlank()) {
                                null
                            } else {
                                val type = if (href.startsWith("/series/")) {
                                    TvType.TvSeries
                                } else {
                                    TvType.Movie
                                }

                                val detailUrl = "$mainUrl$href"
                                val poster = getPoster(detailUrl)

                                newMovieSearchResponse(title, detailUrl, type) {
                                    posterUrl = poster
                                }
                            }
                        }
                    }
                }
                ?.mapNotNull { it.await() }
                .orEmpty()
        }

        return newHomePageResponse(
            request.name,
            items,
            false
        )
    }


    private suspend fun getPoster(url: String): String? {
        posterCache[url]?.let { return it }

        return try {
            val document = app.get(url).document
            val scripts = document.select("script[type=application/ld+json]")

            for (script in scripts) {
                val data = org.json.JSONArray(script.data())

                for (i in 0 until data.length()) {
                    val item = data.optJSONObject(i) ?: continue
                    val type = item.optString("@type")

                    if (type == "Movie" || type == "TVSeries") {
                        val images = item.optJSONArray("image")
                        val poster = images?.optString(0)

                        if (!poster.isNullOrBlank()) {
                            posterCache[url] = poster
                            return poster
                        }
                    }
                }
            }

            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }


    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        var metadata: org.json.JSONObject? = null

        for (script in document.select("script[type=application/ld+json]")) {
            val array = try {
                org.json.JSONArray(script.data())
            } catch (_: Exception) {
                continue
            }

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("@type") == "Movie" ||
                    item.optString("@type") == "TVSeries") {
                    metadata = item
                    break
                }
            }
            if (metadata != null) break
        }

        val movie = metadata
        val title = movie?.optString("name")
            ?.takeIf { it.isNotBlank() }
            ?: document.title().substringBefore(" / IDLIX")
                .removePrefix("Nonton ")

        val images = movie?.optJSONArray("image")
        val poster = images?.optString(0)
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")
                ?.attr("content")

        val backdrop = images?.optString(1)
            ?.takeIf { it.isNotBlank() }

        val genres = movie?.optJSONArray("genre")
        val genreList = if (genres != null) {
            (0 until genres.length()).mapNotNull {
                genres.optString(it).takeIf(String::isNotBlank)
            }
        } else emptyList()

        val year = movie?.optString("datePublished")
            ?.take(4)?.toIntOrNull()

        val durationMatch = Regex("""PT(?:(\d+)H)?(?:(\d+)M)?""")
            .find(movie?.optString("duration").orEmpty())

        val minutes = durationMatch?.let {
            (it.groupValues[1].toIntOrNull() ?: 0) * 60 +
                (it.groupValues[2].toIntOrNull() ?: 0)
        }

        val isSeries = url.contains("/series/") ||
            movie?.optString("@type") == "TVSeries"

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonPattern = Regex(
                """/series/[^/]+/season/(\d+)/episode/1"""
            )

            val seasons = document.select("a[href]")
                .mapNotNull { element ->
                    val href = element.attr("href")
                    val match = seasonPattern.find(href)
                    match?.groupValues?.get(1)?.toIntOrNull()
                        ?.let { it to "$mainUrl$href" }
                }
                .distinctBy { it.first }
                .sortedBy { it.first }

            for ((seasonNumber, firstUrl) in seasons) {
                val found = try {
                    getSeasonEpisodes(firstUrl, seasonNumber)
                } catch (_: Exception) {
                    emptyList()
                }

                for ((episodeNumber, episodeUrl) in found) {
                    episodes.add(
                        newEpisode(episodeUrl) {
                            name = "Episode $episodeNumber"
                            season = seasonNumber
                            episode = episodeNumber
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                backgroundPosterUrl = backdrop
                plot = movie?.optString("description")
                this.year = year
                tags = genreList
            }
        }

        val ratingText = document.select("dt")
            .firstOrNull { it.text().trim() == "Rating" }
            ?.nextElementSibling()
            ?.text()
            .orEmpty()

        val rating = Regex("""\d+(?:\.\d+)?""")
            .find(ratingText)
            ?.value
            ?.toDoubleOrNull()

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = poster
            backgroundPosterUrl = backdrop
            plot = movie?.optString("description")
            this.year = year
            tags = genreList
            duration = minutes
            this.score = Score.from10(rating)
        }
    }



    private suspend fun getSeasonEpisodes(
        firstEpisodeUrl: String,
        season: Int
    ): List<Pair<Int, String>> {
        val document = app.get(firstEpisodeUrl).document
        val pattern = Regex(
            """/series/[^/"\s\\]+/season/(\d+)/episode/(\d+)"""
        )

        val episodes = mutableMapOf<Int, String>()

        for (script in document.select("script")) {
            val content = script.data()
                .replace("\\/", "/")

            for (match in pattern.findAll(content)) {
                val episodeSeason = match.groupValues[1].toIntOrNull()
                val episodeNumber = match.groupValues[2].toIntOrNull()

                if (episodeSeason == season && episodeNumber != null) {
                    episodes[episodeNumber] = mainUrl + match.value
                }
            }
        }

        return episodes.toSortedMap().map { it.key to it.value }
    }

}
