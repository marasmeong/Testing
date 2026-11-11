package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class Idlix : MainAPI() {

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

        val items = heading?.parent()
            ?.select("a[href^=/movie/], a[href^=/series/]")
            ?.mapNotNull { element ->
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

                    newMovieSearchResponse(
                        title,
                        detailUrl,
                        type
                    ) {
                        posterUrl = poster
                    }
                }
            }.orEmpty()

        return newHomePageResponse(
            request.name,
            items,
            false
        )
    }


    private suspend fun getPoster(url: String): String? {
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
                if (item.optString("@type") == "Movie") {
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

        val durationMatch = Regex("""PT(?:(\\d+)H)?(?:(\\d+)M)?""")
            .find(movie?.optString("duration").orEmpty())

        val minutes = durationMatch?.let {
            (it.groupValues[1].toIntOrNull() ?: 0) * 60 +
                (it.groupValues[2].toIntOrNull() ?: 0)
        }

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
        }
    }

}
