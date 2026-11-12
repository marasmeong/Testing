package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async

import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.delay
import org.json.JSONObject

class Idlix : MainAPI() {
    private val cfKiller = CloudflareKiller()

    private val apiHeaders
        get() = mapOf(
            "Accept" to "application/json",
            "Referer" to "$mainUrl/"
        )

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


    private fun idlixLog(stage: String, message: String) {
        android.util.Log.e("IDLIX_DEBUG", "[$stage] $message")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (tryLoadFromApi(data, subtitleCallback, callback)) return true

        val html = try {
            app.get(data, interceptor = cfKiller).text
                .replace("\\\"", "\"")
        } catch (_: Exception) {
            return false
        }

        var found = false
        val patterns = listOf(
            Regex("""sources":\[([^\]]+)\]"""),
            Regex("""playlist":\[([^\]]+)\]"""),
            Regex("""playerSources":\[([^\]]+)\]""")
        )

        for (pattern in patterns) {
            val content = pattern.find(html)?.groupValues?.get(1) ?: continue

            val urls = Regex("""https?://[^\s"',]+\.(?:m3u8|mp4)[^\s"',]*""")
                .findAll(content)

            for (match in urls) {
                val url = match.value
                callback(
                    newExtractorLink(
                        "IDLIX",
                        "IDLIX",
                        url,
                        type = if (url.contains(".m3u8"))
                            ExtractorLinkType.M3U8
                        else ExtractorLinkType.VIDEO
                    ) {
                        referer = "$mainUrl/"
                    }
                )
                found = true
            }
        }

        return found
    }

    private suspend fun tryLoadFromApi(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        idlixLog("API", "Playback started")
            val episode = Regex(
            """/series/([^/]+)/season/(\d+)/episode/(\d+)"""
        ).find(data)

        val isEpisode = episode != null
        val isSeries = data.contains("/series/")

        val slug = when {
            isEpisode -> episode!!.groupValues[1]
            isSeries -> data.substringAfter("/series/").substringBefore("/")
            else -> data.substringAfter("/movie/").substringBefore("/")
        }.substringBefore("?")

        if (slug.isBlank() || slug.startsWith("http")) return false

        val pageUrl = when {
            isEpisode -> "$mainUrl/series/$slug/season/${episode!!.groupValues[2]}/episode/${episode.groupValues[3]}"
            isSeries -> "$mainUrl/series/$slug"
            else -> "$mainUrl/movie/$slug"
        }

        var cookies = emptyMap<String, String>()

        try {
            idlixLog("PAGE", "Request started")
            val page = app.get(pageUrl, interceptor = cfKiller)
            val did = Regex("""\bdid=([^;]+)""")
                .find(page.headers.values("Set-Cookie").joinToString("; "))
                ?.groupValues?.getOrNull(1)

            if (!did.isNullOrBlank()) {
                cookies = mapOf("Cookie" to "did=$did")
            }
        } catch (_: Exception) {}

        return try {
            val apiPath = when {
                isEpisode -> "api/series/$slug/season/${episode!!.groupValues[2]}/episode/${episode.groupValues[3]}"
                isSeries -> "api/series/$slug"
                else -> "api/movies/$slug"
            }

            val headers = apiHeaders +
                mapOf("Referer" to "$pageUrl/") + cookies

            idlixLog("DETAIL", "Request started")
            val detail = JSONObject(
                app.get(
                    "$mainUrl/$apiPath",
                    headers = headers,
                    interceptor = cfKiller
                ).text
            )

            val contentId = detail.optJSONObject("episode")
                ?.optString("id")
                ?.takeIf { it.isNotBlank() }
                ?: detail.optString("id")

            if (contentId.isBlank()) return false

            val contentType = when {
                isEpisode -> "episode"
                isSeries -> "tv_series"
                else -> "movie"
            }

            idlixLog("PLAY_INFO", "Request started")
            val info = JSONObject(
                app.get(
                    "$mainUrl/api/watch/play-info/$contentType/$contentId",
                    headers = headers,
                    interceptor = cfKiller
                ).text
            )

            val token = info.optString("gateToken")
            idlixLog("GATE", "kind=${info.optString("kind")} tokenAvailable=${token.isNotBlank()}")
            if (token.isBlank()) return false

            if (info.optString("kind") == "gate") {
                val unlock = info.optLong("unlockAt")
                val now = info.optLong("serverNow")
                val wait = if (unlock > 0 && now > 0)
                    unlock - now else 15000L

                if (wait > 0) delay(wait + 2000L)
            }

            idlixLog("CLAIM", "Request started")
            var claim = postClaim(token, pageUrl, cookies)

            if (claim.optString("kind") == "pending") {
                val remaining = claim.optLong("remainingMs")
                if (remaining > 0) delay(remaining + 2000L)
                claim = postClaim(token, pageUrl, cookies)
            }

            idlixLog("CLAIM", "kind=${claim.optString("kind")}")
            val redeemUrl = claim.optString("redeemUrl")
            val claimToken = claim.optString("claim")
            android.util.Log.e(
                "IDLIX_DEBUG",
                "Redeem path=${java.net.URI(redeemUrl).path}, " +
                "Redeem URL shape: " +
                "absolute=${redeemUrl.startsWith("http")}, " +
                "startsSlash=${redeemUrl.startsWith("/")}, " +
                "hasQuery=${redeemUrl.contains("?")}, " +
                "length=${redeemUrl.length}"
            )


            if (redeemUrl.isNotBlank() && claimToken.isNotBlank()) {
                try {
                    idlixLog("REDEEM", "Request started")
            val redeemHttp = app.post(
                            java.net.URI("$mainUrl/").resolve("/api" + redeemUrl).toString(),
                            headers = mapOf(
                                "Origin" to mainUrl,
                                "Referer" to "$mainUrl/",
                                "Content-Type" to "application/json"
                            ),
                            json = JsonAsString(
                                JSONObject()
                                    .put("claim", claimToken)
                                    .toString()
                            )
                        )
                    android.util.Log.e(
                        "IDLIX_DEBUG",
                        "Redeem HTTP=${redeemHttp.code}, " +
                        "Content-Type=${redeemHttp.headers["Content-Type"]}, " +
                        "Length=${redeemHttp.text.length}"
                    )
                    val response = JSONObject(redeemHttp.text)
                    if (redeemHttp.code != 200) {
                        idlixLog(
                            "REDEEM_ERROR",
                            "HTTP=${redeemHttp.code} " +
                            "error=${response.optString("error")} " +
                            "message=${response.optString("message")} " +
                            "code=${response.optString("code")}"
                        )
                    }
            idlixLog("REDEEM", "JSON parsed hasUrl=${response.optString("url").isNotBlank()}")

                    val stream = response.optString("url")
            idlixLog("STREAM", "available=${stream.isNotBlank()} m3u8=${stream.contains(".m3u8")} mp4=${stream.contains(".mp4")}")
            idlixLog("SUBTITLE", "count=${response.optJSONArray("subtitles")?.length() ?: 0}")

                    if (stream.isNotBlank()) {
                        response.optJSONArray("subtitles")?.let { subs ->
                            for (i in 0 until subs.length()) {
                                val sub = subs.optJSONObject(i) ?: continue
                                val label = sub.optString("label")
                                    .ifBlank { sub.optString("lang") }
                                val path = sub.optString("path")

                                if (label.isNotBlank() && path.isNotBlank()) {
                                    subtitleCallback(newSubtitleFile(label, path))
                                }
                            }
                        }

                        callback(
                            newExtractorLink(
                                "IDLIX",
                                "IDLIX",
                                stream,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = "$mainUrl/"
                            }
                        )
                        return true
                    }
                } catch (e: Exception) {
                    android.util.Log.e("IDLIX_DEBUG", "Redeem failed: " + e.javaClass.simpleName)
                }
            }

            idlixLog("INITIAL", "available=${claim.optString("initialMasterUrl").isNotBlank()}")
            val initial = claim.optString("initialMasterUrl")
            if (initial.isNotBlank()) {
                callback(
                    newExtractorLink(
                        "IDLIX",
                        "IDLIX",
                        initial,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = "$mainUrl/"
                    }
                )
                return true
            }

            false
        } catch (e: Exception) {
            android.util.Log.e(
                "IDLIX_DEBUG",
                "Playback API failed: " + e.javaClass.simpleName
            )
            false
        }
    }

    private suspend fun postClaim(
        token: String,
        pageUrl: String,
        cookies: Map<String, String>
    ): JSONObject {
        val response = app.post(
            "$mainUrl/api/watch/session/claim",
            headers = apiHeaders + mapOf(
                "Origin" to mainUrl,
                "Referer" to "$pageUrl/",
                "Content-Type" to "application/json"
            ) + cookies,
            json = JsonAsString(
                JSONObject().put("gateToken", token).toString()
            ),
            interceptor = cfKiller
        )

        idlixLog("CLAIM_HTTP", "HTTP=${response.code} type=${response.headers["Content-Type"]}")
            return JSONObject(response.text)
    }


}
