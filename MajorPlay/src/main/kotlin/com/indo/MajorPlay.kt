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
        "/movie" to "Film Terbaru"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get("$mainUrl${request.data}").document

        val movies = doc.select("a.block[href^=/movie/]")
            .distinctBy { it.attr("href") }
            .mapNotNull { card ->
                val img = card.selectFirst("img")
                    ?: return@mapNotNull null

                val title = img.attr("alt").trim()
                val href = card.attr("href")

                if (title.isBlank() || href.isBlank()) {
                    return@mapNotNull null
                }

                newMovieSearchResponse(
                    title,
                    "$mainUrl$href",
                    TvType.Movie
                ) {
                    posterUrl = img.attr("src")
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
