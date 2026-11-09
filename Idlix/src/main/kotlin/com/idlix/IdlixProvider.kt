package com.idlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.delay
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


    private val cfKiller = CloudflareKiller()
    private val apiHeaders = mapOf(
        "Accept" to "application/json",
        "Referer" to "$mainUrl/"
    )

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
        if (tryLoadFromApi(data, subtitleCallback, callback)) return true

        val resp = app.get(data, interceptor = cfKiller)
        val raw = resp.text ?: return true
        val html = raw.replace("\\\"", "\"")

        val sources = listOf(
            Regex("""sources":\[([^\]]+)\]"""),
            Regex("""playlist":\[([^\]]+)\]"""),
            Regex("""playerSources":\[([^\]]+)\]"""),
        )

        for (pattern in sources) {
            val match = pattern.find(html) ?: continue
            val content = match.groupValues[1]
            Regex("""https?://[^\s"',]+\.(?:m3u8|mp4)[^\s"',]*""").findAll(content).forEach { m ->
                callback(newExtractorLink("Idlix", "Idlix", m.value) {
                    this.referer = "$mainUrl/"
                })
            }
            Regex("""playbackUrl":"([^"]+)""").findAll(content).forEach { m ->
                val quality = Regex("""quality":"([^"]+)""").find(content)?.groupValues?.getOrNull(1) ?: "FHD"
                callback(newExtractorLink("Idlix", "Idlix - $quality", m.groupValues[1]) {
                    this.quality = parseQuality(quality)
                    this.referer = "$mainUrl/"
                })
            }
        }

        return true
    }

    private suspend fun tryLoadFromApi(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug: String
        val contentType: String
        val extra: Map<String, String>

        val episodeMatch = Regex("/series/([^/]+)/season/(\\d+)/episode/(\\d+)").find(data)
        if (episodeMatch != null) {
            slug = episodeMatch.groupValues[1]
            contentType = "tv_series"
            extra = mapOf("season" to episodeMatch.groupValues[2], "episode" to episodeMatch.groupValues[3])
        } else if (data.contains("/series/")) {
            slug = data.substringAfter("/series/").substringBefore("/").substringBefore("?")
            contentType = "tv_series"
            extra = emptyMap()
        } else {
            slug = data.substringAfter("/movie/").substringBefore("/").substringBefore("?")
            contentType = "movie"
            extra = emptyMap()
        }
        if (slug.isBlank()) return false

        val pageUrl = if (contentType == "movie") "$mainUrl/movie/$slug"
        else if (extra.containsKey("season")) "$mainUrl/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
        else "$mainUrl/series/$slug"

        var cookieHeader = emptyMap<String, String>()
        try {
            val pageResp = app.get(pageUrl, interceptor = cfKiller)
            val setCookie = pageResp.headers["set-cookie"].orEmpty()
            val did = Regex("""\bdid=([^;]+)""").find(setCookie)?.groupValues?.getOrNull(1)
            if (!did.isNullOrBlank()) {
                cookieHeader = mapOf("Cookie" to "did=$did")
            }
        } catch (_: Exception) {}

        val contentId: String
        try {
            val apiPath = if (contentType == "movie") "api/movies/$slug"
            else if (extra.containsKey("season")) "api/series/$slug/season/${extra["season"]}/episode/${extra["episode"]}"
            else "api/series/$slug"
            val detailResp = app.get("$mainUrl/$apiPath", headers = apiHeaders + mapOf("Referer" to "$pageUrl/") + cookieHeader, interceptor = cfKiller)
            val detailJson = JSONObject(detailResp.text ?: return false)
            contentId = detailJson.optJSONObject("episode")?.optString("id", "")?.ifBlank { null }
                ?: detailJson.optString("id", "")
            if (contentId.isBlank()) return false
        } catch (e: Exception) {
            return false
        }

        var streamUrl: String? = null
        var subtitles: List<Pair<String, String>> = emptyList()
        try {
            val playInfoType = if (extra.containsKey("season")) "episode" else contentType
            val playInfoResp = app.get(
                "$mainUrl/api/watch/play-info/$playInfoType/$contentId",
                headers = apiHeaders + mapOf("Referer" to "$pageUrl/") + cookieHeader,
                interceptor = cfKiller
            )
            val playInfo = JSONObject(playInfoResp.text ?: return false)
            val gateToken = playInfo.optString("gateToken", "")
            if (gateToken.isBlank()) return false

            val kind = playInfo.optString("kind", "")
            if (kind == "gate") {
                val unlockAt = playInfo.optLong("unlockAt", 0L)
                val serverNow = playInfo.optLong("serverNow", 0L)
                val waitMs = if (unlockAt > 0 && serverNow > 0) unlockAt - serverNow else 15000L
                if (waitMs > 0) delay(waitMs + 2000)
            }

            var claimJson = postClaim(gateToken, pageUrl, cookieHeader)

            if (claimJson.optString("kind") == "pending") {
                val remainingMs = claimJson.optLong("remainingMs", 0)
                if (remainingMs > 0) delay(remainingMs + 2000)
                claimJson = postClaim(gateToken, pageUrl, cookieHeader)
            }

            val redeemUrl = claimJson.optString("redeemUrl", "")
            val claim = claimJson.optString("claim", "")
            val initialMaster = claimJson.optString("initialMasterUrl", "")

            if (redeemUrl.isNotBlank() && claim.isNotBlank()) {
                try {
                    val pentosResp = app.post(
                        redeemUrl,
                        headers = mapOf(
                            "Origin" to mainUrl,
                            "Referer" to "$mainUrl/",
                            "Content-Type" to "application/json"
                        ),
                        json = JsonAsString("""{"claim":"$claim"}""")
                    )
                    val pentosText = pentosResp.text ?: ""
                    val pentosJson = JSONObject(pentosText)
                    streamUrl = pentosJson.optString("url", "").ifBlank { null }
                    val subsArray = pentosJson.optJSONArray("subtitles")
                    subtitles = if (subsArray != null) {
                        (0 until subsArray.length()).mapNotNull { i ->
                            val sub = subsArray.optJSONObject(i) ?: return@mapNotNull null
                            val lang = sub.optString("label").ifBlank { sub.optString("lang", "") }
                            val path = sub.optString("path", "")
                            if (lang.isNotBlank() && path.isNotBlank()) Pair(lang, path) else null
                        }
                    } else emptyList()
                } catch (_: Exception) {}
                if (streamUrl != null) {
                    subtitles.forEach { (lang, path) ->
                        subtitleCallback(newSubtitleFile(lang, path))
                    }
                    callback(newExtractorLink("Idlix", "Idlix", streamUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = "$mainUrl/"
                    })
                    return true
                }
            }

            if (initialMaster.isNotBlank()) {
                callback(newExtractorLink("Idlix", "Idlix", initialMaster, type = ExtractorLinkType.M3U8) {
                    this.referer = "$mainUrl/"
                })
                return true
            }
        } catch (e: Exception) {
            return false
        }

        return false
    }

    private suspend fun postClaim(gateToken: String, pageUrl: String, cookieHeader: Map<String, String>): JSONObject {
        val claimResp = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = apiHeaders + mapOf(
                "Origin" to mainUrl,
                "Referer" to "$pageUrl/",
                "Content-Type" to "application/json"
            ) + cookieHeader,
            json = JsonAsString("""{"gateToken":"$gateToken"}"""),
            interceptor = cfKiller
        )
        return JSONObject(claimResp.text ?: "{}")
    }

    private fun parseQuality(q: String): Int {
        return when {
            q.contains("4K", true) || q.contains("2160", true) -> 4
            q.contains("1080", true) -> 3
            q.contains("720", true) -> 2
            q.contains("480", true) || q.contains("360", true) -> 1
            else -> 3
        }
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        return "$mainUrl$url"
    }
}
