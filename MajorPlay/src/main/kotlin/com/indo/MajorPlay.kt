package com.indo

import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.delay
import org.json.JSONObject
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse

class MajorPlay : MainAPI() {
    override var mainUrl = "https://z2.idlixku.com"
    override var name = "MajorPlay"
    override var lang = "id"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "featured" to "Featured",
        "trending-now" to "Trending Now",
        "trending-country" to "Trending in Indonesia",
        "network-originals" to "Network Originals",
        "collections" to "Collections",
        "latest-movies" to "Film Terbaru"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val response = app.get(
            "$mainUrl/api/homepage",
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/"
            )
        )

        val homepage = JSONObject(response.text)

        val section = sequenceOf("above", "below")
            .flatMap { area ->
                val array = homepage.optJSONArray(area)
                (0 until (array?.length() ?: 0))
                    .asSequence()
                    .mapNotNull { array?.optJSONObject(it) }
            }
            .firstOrNull { it.optString("slug") == request.data }

        val data = section?.optJSONArray("data")

        val movies = (0 until (data?.length() ?: 0)).mapNotNull { i ->
            val item = data?.optJSONObject(i) ?: return@mapNotNull null
            if (item.optString("contentType") != "movie") {
                return@mapNotNull null
            }

            val content = item.optJSONObject("content")
                ?: return@mapNotNull null

            val title = content.optString("title")
            val slug = content.optString("slug")
            if (title.isBlank() || slug.isBlank()) {
                return@mapNotNull null
            }

            val poster = content.optString("posterPath")

            newMovieSearchResponse(
                title,
                "$mainUrl/movie/$slug",
                TvType.Movie
            ) {
                posterUrl = if (poster.startsWith("/")) {
                    "https://image.tmdb.org/t/p/w500$poster"
                } else null
            }
        }

        return newHomePageResponse(
            request.name,
            movies,
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse? {
        val slug = url.substringBefore("?").trimEnd('/').substringAfterLast("/")
        val response = app.get("$mainUrl/api/movies/$slug")
        val data = JSONObject(response.text)

        val id = data.optString("id")
        if (id.isBlank()) return null

        val title = data.optString("title", slug)
        val poster = data.optString("posterPath")
        val backdrop = data.optString("backdropPath")

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            id
        ) {
            posterUrl = if (poster.startsWith("/"))
                "https://image.tmdb.org/t/p/w500$poster" else null

            backgroundPosterUrl = if (backdrop.startsWith("/"))
                "https://image.tmdb.org/t/p/original$backdrop" else null

            plot = data.optString("overview")
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val info = JSONObject(
            app.get("$mainUrl/api/watch/play-info/movie/$data").text
        )

        val gateToken = info.optString("gateToken")
        if (gateToken.isBlank()) return false

        val serverNow = info.optLong("serverNow")
        val unlockAt = info.optLong("unlockAt")

        if (serverNow > 0 && unlockAt > serverNow) {
            delay((unlockAt - serverNow).coerceAtMost(60000L))
        }

        val claimBody = JSONObject()
            .put("gateToken", gateToken)
            .toString()

        val claimResponse = JSONObject(
            app.post(
                "$mainUrl/api/watch/session/claim",
                json = claimBody,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Referer" to "$mainUrl/"
                )
            ).text
        )

        val claim = claimResponse.optString("claim")
        val redeemUrl = claimResponse.optString("redeemUrl")

        if (claim.isBlank() || redeemUrl.isBlank()) return false

        val playBody = JSONObject()
            .put("claim", claim)
            .put("mode", "browser")
            .toString()

        val playResponse = JSONObject(
            app.post(
                redeemUrl,
                json = playBody,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
            ).text
        )

        val hlsUrl = playResponse.optString("url")
        if (hlsUrl.isBlank()) return false

        return loadExtractor(
            hlsUrl,
            mainUrl,
            subtitleCallback,
            callback
        )
    }

}
