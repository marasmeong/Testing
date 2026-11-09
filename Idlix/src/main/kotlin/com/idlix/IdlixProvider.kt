package com.idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class IdlixProvider : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "Idlix"
    override var lang = "id"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var savedDidCookie: String? = null

    private val ua = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl/api/homepage" to "Idlix"
    )

    private fun poster(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return if (path.startsWith("http")) path
        else "https://image.tmdb.org/t/p/w500$path"
    }

    private fun JSONObject.str(key: String): String =
        optString(key).takeUnless { it == "null" } ?: ""

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val json = JSONObject(
            app.get(request.data, headers = mapOf("User-Agent" to ua)).text
        )

        val sections = mutableListOf<HomePageList>()

        for (group in listOf("above", "below")) {
            val blocks = json.optJSONArray(group) ?: continue

            for (i in 0 until blocks.length()) {
                val block = blocks.optJSONObject(i) ?: continue
                val items = block.optJSONArray("data") ?: continue
                val results = mutableListOf<SearchResponse>()

                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val type = item.str("contentType")
                    val content = item.optJSONObject("content") ?: item

                    if (type == "episode") {
                        val series = item.optJSONObject("series") ?: continue
                        val slug = series.str("slug")
                        val season = item.optJSONObject("season")
                            ?.optInt("seasonNumber", 0) ?: 0
                        val episode = item.optInt("episodeNumber", 0)

                        if (slug.isBlank() || season < 1 || episode < 1) continue

                        val title = series.str("title") +
                            " S${season}E${episode}: " + item.str("name")

                        results.add(
                            newTvSeriesSearchResponse(
                                title,
                                "$mainUrl/series/$slug/season/$season/episode/$episode",
                                TvType.TvSeries
                            ) {
                                posterUrl = poster(series.str("posterPath"))
                            }
                        )
                        continue
                    }

                    val slug = content.str("slug")
                    val title = content.str("title")
                    if (slug.isBlank() || title.isBlank()) continue

                    val isMovie = type == "movie"
                    val url = "$mainUrl/${if (isMovie) "movie" else "series"}/$slug"

                    if (isMovie) {
                        results.add(
                            newMovieSearchResponse(title, url, TvType.Movie) {
                                posterUrl = poster(content.str("posterPath"))
                            }
                        )
                    } else {
                        results.add(
                            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                                posterUrl = poster(content.str("posterPath"))
                            }
                        )
                    }
                }

                if (results.isNotEmpty()) {
                    val title = block.str("title")
                        .ifBlank { block.str("slug") }

                    sections.add(HomePageList(title, results))
                }
            }
        }

        return newHomePageResponse(sections, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "$mainUrl/api/search?q=$encoded&page=1&limit=50"

        val json = JSONObject(
            app.get(url, headers = mapOf("User-Agent" to ua)).text
        )

        val results = json.optJSONArray("results")
            ?: return emptyList()

        val list = mutableListOf<SearchResponse>()

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue

            val title = item.str("title")
            val slug = item.str("slug")
            val type = item.str("contentType")

            if (title.isBlank() || slug.isBlank()) continue

            when (type) {
                "movie" -> {
                    list.add(
                        newMovieSearchResponse(
                            title,
                            "$mainUrl/movie/$slug",
                            TvType.Movie
                        ) {
                            posterUrl = poster(item.str("posterPath"))
                        }
                    )
                }

                "tv_series" -> {
                    list.add(
                        newTvSeriesSearchResponse(
                            title,
                            "$mainUrl/series/$slug",
                            TvType.TvSeries
                        ) {
                            posterUrl = poster(item.str("posterPath"))
                        }
                    )
                }
            }
        }

        return list
    }


    override suspend fun load(url: String): LoadResponse? {
        val path = java.net.URI(url).path.trimEnd('/')
        val parts = path.split("/").filter { it.isNotBlank() }

        if (parts.size < 2) return null

        val isMovie = parts[0] == "movie"
        if (!isMovie && parts[0] != "series") return null

        val slug = parts[1]
        val apiPath = if (isMovie) "movies" else "series"

        val data = JSONObject(
            app.get(
                "$mainUrl/api/$apiPath/$slug",
                headers = mapOf("User-Agent" to ua)
            ).text
        )

        val title = data.str("title")
        val description = data.str("overview")
        val image = poster(data.str("posterPath"))
        val background = poster(data.str("backdropPath"))
        val year = (
            if (isMovie) data.str("releaseDate")
            else data.str("firstAirDate")
        ).take(4).toIntOrNull()

        if (isMovie) {
            return newMovieLoadResponse(
                title,
                "$mainUrl/movie/$slug",
                TvType.Movie,
                "movie:" + data.str("id")
            ) {
                posterUrl = image
                backgroundPosterUrl = background
                plot = description
                this.year = year
            }
        }

        val episodes = mutableListOf<Episode>()
        val seasons = data.optJSONArray("seasons")

        if (seasons != null) {
            for (i in 0 until seasons.length()) {
                val seasonInfo = seasons.optJSONObject(i) ?: continue
                val seasonNumber = seasonInfo.optInt("seasonNumber", -1)
                if (seasonNumber < 0) continue

                val seasonData = JSONObject(
                    app.get(
                        "$mainUrl/api/series/$slug/season/$seasonNumber",
                        headers = mapOf("User-Agent" to ua)
                    ).text
                ).optJSONObject("season") ?: continue

                val items = seasonData.optJSONArray("episodes") ?: continue

                for (j in 0 until items.length()) {
                    val ep = items.optJSONObject(j) ?: continue
                    val episodeNumber = ep.optInt("episodeNumber", 0)
                    if (episodeNumber < 1) continue

                    episodes.add(
                        newEpisode("episode:" + ep.str("id")) {
                            name = ep.str("name")
                            season = seasonNumber
                            episode = episodeNumber
                            posterUrl = poster(ep.str("stillPath"))
                            this.description = ep.str("overview")
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(
            title,
            "$mainUrl/series/$slug",
            TvType.TvSeries,
            episodes
        ) {
            posterUrl = image
            backgroundPosterUrl = background
            plot = description
            this.year = year
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val contentType = when {
            data.startsWith("movie:") -> "movie"
            data.startsWith("episode:") -> "episode"
            else -> return false
        }
        val contentId = data.substringAfter(":")

        val headers = mapOf(
            "User-Agent" to ua,
            "Accept" to "application/json"
        )

        val infoResponse = app.get(
            "$mainUrl/api/watch/play-info/$contentType/$contentId",
            headers = headers
        )

        val newDidCookie = infoResponse.headers.values("Set-Cookie")
            .firstOrNull { it.trim().startsWith("did=") }
            ?.trim()
            ?.substringBefore(";")

        if (newDidCookie != null) {
            savedDidCookie = newDidCookie
        }

        val sessionHeaders = savedDidCookie?.let {
            headers + ("Cookie" to it)
        } ?: headers

        val info = JSONObject(infoResponse.text)

        if (info.str("kind") != "gate") return false

        val waitMs = (
            info.optLong("unlockAt") -
            info.optLong("serverNow") + 1500L
        ).coerceAtLeast(0L)

        kotlinx.coroutines.delay(waitMs)


        var claim: JSONObject? = null

        for (attempt in 1..12) {
            val response = JSONObject(
                app.post(
                    "$mainUrl/api/watch/session/claim",
                    headers = sessionHeaders + ("Content-Type" to "application/json"),
                    requestBody = JSONObject()
                        .put("gateToken", info.str("gateToken"))
                        .toString()
                        .toRequestBody("application/json".toMediaType())
                ).text
            )

            if (response.str("kind") == "pentos") {
                claim = response
                break
            }

            val remaining = response.optLong("remainingMs", 250L)
            val delayMs = (remaining + 125L).coerceIn(250L, 5000L)
            kotlinx.coroutines.delay(delayMs)
        }

        val readyClaim = claim ?: return false

        val redeemUrl = readyClaim.str("redeemUrl")
        val claimToken = readyClaim.str("claim")

        if (redeemUrl.isBlank() || claimToken.isBlank()) return false

        val result = JSONObject(
            app.post(
                redeemUrl,
                headers = headers + ("Content-Type" to "application/json"),
                requestBody = JSONObject()
                    .put("claim", claimToken)
                    .put("mode", "browser")
                    .toString()
                    .toRequestBody("application/json".toMediaType())
            ).text
        )

        val videoUrl = result.str("url")
        if (videoUrl.isBlank()) return false

        callback(
            newExtractorLink(
                "Idlix",
                "Idlix HLS",
                videoUrl,
                ExtractorLinkType.M3U8
            ) {
                referer = mainUrl
            }
        )

        return true
    }

}
