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
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.app.AlertDialog
import com.lagradost.cloudstream3.CommonActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class Idlix : MainAPI() {
    private val cfKiller = CloudflareKiller()


    suspend fun openIdlixLoginWebView(): Boolean =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val activity = CommonActivity.activity
                if (activity == null || activity.isFinishing) {
                    continuation.resume(false)
                    return@suspendCancellableCoroutine
                }

                val webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = WebViewClient()
                }

                val manager = CookieManager.getInstance()
                manager.setAcceptCookie(true)
                manager.setAcceptThirdPartyCookies(webView, true)

                val layout = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                }

                val toolbar = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }

                val back = android.widget.Button(activity).apply {
                    text = "Kembali"
                    setOnClickListener {
                        if (webView.canGoBack()) webView.goBack()
                    }
                }

                val done = android.widget.Button(activity).apply {
                    text = "Selesai"
                }

                toolbar.addView(back)
                toolbar.addView(done)
                layout.addView(toolbar)
                layout.addView(
                    webView,
                    android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        (activity.resources.displayMetrics.heightPixels * 0.60f).toInt()
                    )
                )

                val dialog = AlertDialog.Builder(activity)
                    .setTitle("Login IDLIX")
                    .setView(layout)
                    .create()

                var completed = false

                fun finish(success: Boolean) {
                    if (completed) return
                    completed = true
                    manager.flush()
                    dialog.dismiss()
                    webView.destroy()
                    if (continuation.isActive) {
                        continuation.resume(success)
                    }
                }

                done.setOnClickListener {
                    finish(!manager.getCookie(mainUrl).isNullOrBlank())
                }

                dialog.setOnCancelListener { finish(false) }
                continuation.invokeOnCancellation {
                    activity.runOnUiThread {
                        if (!completed) finish(false)
                    }
                }

                dialog.show()
                dialog.window?.setLayout(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (activity.resources.displayMetrics.heightPixels * 0.75f).toInt()
                )
                dialog.window?.clearFlags(
                    android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                )
                webView.loadUrl(mainUrl)
            }
        }

    private fun loginCookies(): Map<String, String> {
        val cookie = runCatching {
            CookieManager.getInstance().getCookie(mainUrl)
        }.getOrNull()
        return if (cookie.isNullOrBlank()) emptyMap()
        else mapOf("Cookie" to cookie)
    }



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


    // HLS proxy: process-local so playback survives MainAPI instance recreation.
    private companion object HlsProxy {
        private val lock = Any()
        private var serverPort = 0
        private val sessions = java.util.concurrent.ConcurrentHashMap<String, HlsSession>()
        private val userAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
        private val permittedHosts = setOf(
            "e2e.majorplay.net",
            "g5.akademivo.website",
            "g7.akademivo.website",
            "g7.edubambu.store",
            "g7.aspireheightsacademy.digital"
        )

        private class HlsSession(val origin: String, val signedQuery: String) {
            val resources = java.util.concurrent.ConcurrentHashMap<String, String>()
            val created = System.currentTimeMillis()
            fun register(url: String): String {
                val id = java.util.UUID.randomUUID().toString().replace("-", "")
                resources[id] = url
                return id
            }
        }

        private fun allowed(url: java.net.URI): Boolean {
            val host = url.host?.lowercase(java.util.Locale.ROOT) ?: return false
            if (!url.scheme.equals("https", true) ||
                (url.port != -1 && url.port != 443) ||
                url.userInfo != null ||
                url.rawFragment != null ||
                host.length > 253 ||
                !host.matches(Regex("""[a-z0-9.-]+""")) ||
                host.contains("..") ||
                host.startsWith(".") ||
                host.endsWith(".")) return false

            val knownHost = host in permittedHosts
            val dynamicCdn = host.matches(
                Regex("""g[0-9]+\.[a-z0-9-]+\.[a-z]{2,}""")
            )
            if (!knownHost && !dynamicCdn) return false

            return try {
                java.net.InetAddress.getAllByName(host).all { address ->
                    !address.isAnyLocalAddress &&
                    !address.isLoopbackAddress &&
                    !address.isSiteLocalAddress &&
                    !address.isLinkLocalAddress &&
                    !address.isMulticastAddress
                }
            } catch (_: Exception) {
                false
            }
        }

        private fun signedUrl(base: String, reference: String, session: HlsSession): String {
            val target = java.net.URI(base).resolve(reference)
            require(allowed(target)) { "HLS host not permitted: ${target.host}" }
            val query = target.rawQuery.orEmpty()
            val names = query.split('&').map { it.substringBefore('=') }.toSet()
            val inherited = session.signedQuery.split('&').filter {
                it.isNotBlank() && it.substringBefore('=') !in names
            }
            val merged = (listOf(query).filter { it.isNotBlank() } + inherited).joinToString("&")
            return java.net.URI(target.scheme, target.rawAuthority, target.rawPath, null, target.rawFragment)
                .toASCIIString().substringBefore('#') + if (merged.isNotEmpty()) "?$merged" else ""
        }

        private fun rewritePlaylist(body: String, base: String, session: HlsSession, port: Int, sid: String): String {
            fun local(ref: String): String {
                val url = signedUrl(base, ref, session)
                return "http://127.0.0.1:$port/$sid/${session.register(url)}"
            }
            val uriAttribute = Regex("""URI="([^"]+)"""")
            return body.lineSequence().map { line ->
                if (line.startsWith("#")) {
                    uriAttribute.replace(line) { match ->
                        "URI=\"${local(match.groupValues[1])}\""
                    }
                } else if (line.isNotBlank()) {
                    local(line.trim())
                } else line
            }.joinToString("\n")
        }

        private fun reply(out: java.io.OutputStream, code: Int, reason: String, type: String, length: Long) {
            out.write(("HTTP/1.1 $code $reason\r\n" +
                "Content-Type: $type\r\n" +
                (if (length >= 0) "Content-Length: $length\r\n" else "") +
                "Access-Control-Allow-Origin: *\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
        }

        private fun handle(socket: java.net.Socket) {
            socket.use { client ->
                client.soTimeout = 20000
                val input = client.getInputStream().bufferedReader(Charsets.US_ASCII)
                val request = input.readLine().orEmpty().split(' ')
                val method = request.getOrNull(0).orEmpty()
                val path = request.getOrNull(1).orEmpty().substringBefore('?')
                var range: String? = null
                for (i in 0 until 60) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", true)) range = line.substringAfter(':').trim()
                }
                val out = client.getOutputStream()
                val parts = path.split('/').filter { it.isNotEmpty() }
                val session = if (parts.size == 2) sessions[parts[0]] else null
                val target = parts.getOrNull(1)?.let { session?.resources?.get(it) }
                if (method != "GET" && method != "HEAD" || session == null || target == null ||
                    System.currentTimeMillis() - session.created > 3_600_000L) {
                    reply(out, 404, "Not Found", "text/plain", 0)
                    return
                }
                var url: String = target!!
                var conn: java.net.HttpURLConnection? = null
                try {
                    var redirects = 0
                    while (true) {
                        if (redirects++ >= 5) error("Too many redirects")
                        val uri = java.net.URI(url)
                        require(allowed(uri)) { "Disallowed HLS host" }
                        val current = (uri.toURL().openConnection() as java.net.HttpURLConnection).apply {
                            instanceFollowRedirects = false
                            connectTimeout = 12000
                            readTimeout = 20000
                            setRequestProperty("User-Agent", userAgent)
                            setRequestProperty("Origin", session.origin)
                            setRequestProperty("Referer", session.origin + "/")
                            setRequestProperty("Accept", "*/*")
                            range?.let { setRequestProperty("Range", it) }
                        }
                        conn = current
                        val status = current.responseCode
                        if (status in 300..399) {
                            val location = current.getHeaderField("Location") ?: error("Redirect without location")
                            url = signedUrl(url, location, session)
                            current.disconnect()
                        } else break
                    }
                    val response = conn ?: error("No upstream")
                    val status = response.responseCode
                    val contentType = response.contentType.orEmpty()
                    val isPlaylist = status == 200 && range == null &&
                        (contentType.contains("mpegurl", true) || url.substringBefore('?').endsWith(".json"))
                    if (isPlaylist) {
                        val bytes = response.inputStream.use { stream ->
                            val buffer = ByteArray(8192)
                            val output = java.io.ByteArrayOutputStream()
                            while (true) {
                                val count = stream.read(buffer)
                                if (count == -1) break
                                require(output.size() + count <= 2_000_000) { "Playlist too large" }
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        }
                        val text = bytes.toString(Charsets.UTF_8)
                        if (text.trimStart().startsWith("#EXTM3U")) {
                            val variants = text.lineSequence()
                                .filter { it.startsWith("#EXT-X-STREAM-INF:") }
                                .map { line ->
                                    val resolution = Regex("""RESOLUTION=(\d+x\d+)""")
                                        .find(line)?.groupValues?.get(1) ?: "unknown"
                                    val bandwidth = Regex("""BANDWIDTH=(\d+)""")
                                        .find(line)?.groupValues?.get(1) ?: "unknown"
                                    "$resolution bandwidth=$bandwidth"
                                }.toList()
                            android.util.Log.e(
                                "IDLIX_DEBUG",
                                "[HLS_QUALITY] variants=${variants.size} details=${variants.joinToString("; ")}"
                            )
                            val rewritten = rewritePlaylist(text, url, session, serverPort, parts[0])
                                .toByteArray(Charsets.UTF_8)
                            reply(out, 200, "OK", "application/vnd.apple.mpegurl", rewritten.size.toLong())
                            if (method == "GET") out.write(rewritten)
                        } else {
                            reply(out, 502, "Bad Gateway", "text/plain", 0)
                        }
                    } else if (status == 200 || status == 206) {
                        val size = response.contentLengthLong
                        out.write(("HTTP/1.1 $status ${if (status == 206) "Partial Content" else "OK"}\r\n" +
                            "Content-Type: ${contentType.ifBlank { "application/octet-stream" }}\r\n" +
                            (if (size >= 0) "Content-Length: $size\r\n" else "") +
                            (response.getHeaderField("Content-Range")?.let { "Content-Range: $it\r\n" } ?: "") +
                            "Accept-Ranges: bytes\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII))
                        if (method == "GET") response.inputStream.use { it.copyTo(out, 64 * 1024) }
                    } else {
                        android.util.Log.e("IDLIX_DEBUG", "[HLS_PROXY] upstream=$status")
                        reply(out, 502, "Bad Gateway", "text/plain", 0)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("IDLIX_DEBUG", "[HLS_PROXY] error=${e.javaClass.simpleName} message=${e.message} at=${e.stackTrace.firstOrNull()?.let { "${it.className}:${it.lineNumber}" }}")
                    try { reply(out, 502, "Bad Gateway", "text/plain", 0) } catch (_: Exception) { }
                } finally {
                    conn?.disconnect()
                }
            }
        }

        fun startProxy(master: String, origin: String): String {
            val uri = java.net.URI(master)
            require(allowed(uri) && uri.host == "e2e.majorplay.net" && !uri.rawQuery.isNullOrBlank())
            synchronized(lock) {
                if (serverPort == 0) {
                    val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
                    serverPort = server.localPort
                    Thread {
                        while (!server.isClosed) {
                            try {
                                val socket = server.accept()
                                Thread { handle(socket) }.apply { isDaemon = true; start() }
                            } catch (_: Exception) { break }
                        }
                    }.apply { isDaemon = true; name = "IDLIX-HLS-Proxy"; start() }
                }
                sessions.entries.removeIf { System.currentTimeMillis() - it.value.created > 3_600_000L }
                val sid = java.util.UUID.randomUUID().toString().replace("-", "")
                val session = HlsSession(origin, uri.rawQuery)
                sessions[sid] = session
                return "http://127.0.0.1:$serverPort/$sid/${session.register(master)}"
            }
        }
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

        idlixLog("FALLBACK_RESULT", "found=$found")
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
            val pageStart = System.currentTimeMillis()
            val page = app.get(pageUrl, interceptor = cfKiller)
            idlixLog("PAGE_HTTP", "HTTP=${page.code} ms=${System.currentTimeMillis() - pageStart}")
            val did = Regex("""\bdid=([^;]+)""")
                .find(page.headers.values("Set-Cookie").joinToString("; "))
                ?.groupValues?.getOrNull(1)

            idlixLog("SESSION", "didCookie=${!did.isNullOrBlank()}")
            val mergedCookies = linkedMapOf<String, String>()
                emptyMap<String, String>()["Cookie"]
                    ?.split(";")
                    ?.forEach { part ->
                        val pair = part.trim().split("=", limit = 2)
                        if (pair.size == 2 && pair[0].isNotBlank()) {
                            mergedCookies[pair[0]] = pair[1]
                        }
                    }
                if (!did.isNullOrBlank()) {
                    mergedCookies["did"] = did
                }
                if (mergedCookies.isNotEmpty()) {
                    cookies = mapOf(
                        "Cookie" to mergedCookies.entries.joinToString("; ") {
                            "${it.key}=${it.value}"
                        }
                    )
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
            val detailStart = System.currentTimeMillis()
            val detailHttp = app.get(
                "$mainUrl/$apiPath",
                headers = headers,
                interceptor = cfKiller
            )
            idlixLog("DETAIL_HTTP", "HTTP=${detailHttp.code} ms=${System.currentTimeMillis() - detailStart} bytes=${detailHttp.text.length}")
            val detail = JSONObject(detailHttp.text)

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
            val infoStart = System.currentTimeMillis()
            val infoHttp = app.get(
                "$mainUrl/api/watch/play-info/$contentType/$contentId",
                headers = headers,
                interceptor = cfKiller
            )
            idlixLog("PLAY_INFO_HTTP", "HTTP=${infoHttp.code} ms=${System.currentTimeMillis() - infoStart} bytes=${infoHttp.text.length}")
            val info = JSONObject(infoHttp.text)

            val token = info.optString("gateToken")
            idlixLog("GATE", "kind=${info.optString("kind")} tokenAvailable=${token.isNotBlank()}")
            if (token.isBlank()) return false

            if (info.optString("kind") == "gate") {
                val unlock = info.optLong("unlockAt")
                val now = info.optLong("serverNow")
                val wait = if (unlock > 0 && now > 0)
                    unlock - now else 15000L

                idlixLog("GATE_WAIT", "waitMs=${if (wait > 0) wait + 2000L else 0L}")
            if (wait > 0) delay(wait + 2000L)
            }

            idlixLog("CLAIM", "Request started")
            val claimStart = System.currentTimeMillis()
            var claim = postClaim(token, pageUrl, cookies)
            idlixLog("CLAIM_TIME", "ms=${System.currentTimeMillis() - claimStart}")

            if (claim.optString("kind") == "pending") {
                val remaining = claim.optLong("remainingMs")
            idlixLog("CLAIM_PENDING", "remainingMs=$remaining")
                if (remaining > 0) delay(remaining + 2000L)
                claim = postClaim(token, pageUrl, cookies)
            }

            idlixLog("CLAIM", "kind=${claim.optString("kind")}")
            val redeemUrl = claim.optString("redeemUrl")
            val claimToken = claim.optString("claim")
            idlixLog("CLAIM_FIELDS", "redeemAvailable=${redeemUrl.isNotBlank()} claimAvailable=${claimToken.isNotBlank()} initialAvailable=${claim.optString("initialMasterUrl").isNotBlank()}")
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
            val redeemStart = System.currentTimeMillis()
            val redeemHttp = app.post(
                            java.net.URI("$mainUrl/").resolve("/api" + redeemUrl).toString(),
                            headers = apiHeaders + mapOf(
                                "Origin" to mainUrl,
                                "Referer" to "$pageUrl/",
                                "Content-Type" to "application/json"
                            ) + cookies,
                            interceptor = cfKiller,
                            json = JsonAsString(
                                JSONObject()
                                    .put("claim", claimToken)
                                    .put("mode", "browser")
                                    .toString()
                            )
                        )
                    android.util.Log.e(
                        "IDLIX_DEBUG",
                        "Redeem HTTP=${redeemHttp.code}, " +
                        "Content-Type=${redeemHttp.headers["Content-Type"]}, " +
                        "Length=${redeemHttp.text.length}"
                    )
                    idlixLog("REDEEM_TIME", "ms=${System.currentTimeMillis() - redeemStart} HTTP=${redeemHttp.code}")
            val response = JSONObject(redeemHttp.text)
                    idlixLog(
                        "REDEEM_KEYS",
                        "fields=" + response.keys().asSequence().toList().joinToString(",")
                    )

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
            if (stream.isNotBlank()) {
                val uri = java.net.URI(stream)
                idlixLog(
                    "STREAM_SHAPE",
                    "host=${uri.host} path=${uri.path} hasQuery=${uri.query != null}"
                )
            }

            if (stream.isNotBlank() && java.net.URI(stream).host == "e2e.majorplay.net") {
                try {
                    val configHttp = app.get(
                        stream,
                        headers = apiHeaders + mapOf("Referer" to "$mainUrl/"),
                        interceptor = cfKiller
                    )


                                val body = configHttp.text
                    val trimmed = body.trimStart()
                    val contentType = configHttp.headers["content-type"].orEmpty()
                    val finalUri = java.net.URI(configHttp.url)

                    idlixLog("MP_HTTP", "status=${configHttp.code} bytes=${body.length}")
                    idlixLog("MP_HEADERS", "contentType=$contentType contentLength=${configHttp.headers["content-length"].orEmpty()}")
                    idlixLog("MP_REDIRECT", "host=${finalUri.host} path=${finalUri.path} hasQuery=${finalUri.query != null}")
                    idlixLog("MP_FORMAT", "jsonObject=${trimmed.startsWith("{")} jsonArray=${trimmed.startsWith("[")} html=${trimmed.startsWith("<")} empty=${trimmed.isEmpty()}")
                    idlixLog("MP_MARKERS", "m3u8=${body.contains(".m3u8", true)} mp4=${body.contains(".mp4", true)} mpd=${body.contains(".mpd", true)} iframe=${body.contains("iframe", true)} source=${body.contains("source", true)} script=${body.contains("<script", true)}")
                    idlixLog("MP_SECURITY", "cloudflare=${body.contains("cloudflare", true)} forbidden=${body.contains("forbidden", true)} expired=${body.contains("expired", true)} unauthorized=${body.contains("unauthorized", true)}")

                    fun inspectJson(value: Any?, path: String, depth: Int = 0) {
                        if (depth > 4) return
                        when (value) {
                            is org.json.JSONObject -> {
                                val keys = value.keys().asSequence().toList()
                                idlixLog("MP_OBJECT", "$path keys=${keys.joinToString(",")}")
                                keys.take(30).forEach { key ->
                                    inspectJson(value.opt(key), "$path.$key", depth + 1)
                                }
                            }
                            is org.json.JSONArray -> {
                                idlixLog("MP_ARRAY", "$path count=${value.length()}")
                                for (i in 0 until minOf(value.length(), 3)) {
                                    inspectJson(value.opt(i), "$path[$i]", depth + 1)
                                }
                            }
                            is String -> {
                                val uri = try {
                                    java.net.URI(value)
                                } catch (_: Exception) {
                                    null
                                }
                                if (uri?.scheme == "https" || uri?.scheme == "http") {
                                    idlixLog("MP_URL_FIELD", "$path host=${uri.host} path=${uri.path} hasQuery=${uri.query != null}")
                                } else {
                                    idlixLog("MP_STRING", "$path length=${value.length} m3u8=${value.contains(".m3u8", true)} mp4=${value.contains(".mp4", true)}")
                                }
                            }
                            is Number -> idlixLog("MP_NUMBER", path)
                            is Boolean -> idlixLog("MP_BOOLEAN", "$path=$value")
                            else -> idlixLog("MP_NULL", path)
                        }
                    }

                    try {
                        inspectJson(org.json.JSONTokener(body).nextValue(), "root")
                    } catch (e: Exception) {
                        idlixLog("MP_PARSE", "failed=${e.javaClass.simpleName}")
                    }

                    val safePreview = trimmed.take(500)
                        .replace(Regex("""https?://[^\s"'<>]+"""), "[URL]")
                        .replace(Regex("""(?i)(token|secret|signature|authorization|claim|session|key|t)\s*[:=]\s*[^\s&,"'<>]+"""), "[REDACTED]")
                        .replace(Regex("""[A-Za-z0-9_-]{24,}"""), "[LONG_VALUE]")

                    idlixLog("MP_PREVIEW", safePreview.replace("\n", " ").replace("\r", " "))

                } catch (e: Exception) {
                    idlixLog("MP_ERROR", "${e.javaClass.simpleName}: ${e.message?.take(100)}")
                }
            }

            idlixLog("SUBTITLE", "count=${response.optJSONArray("subtitles")?.length() ?: 0}")

                    if (stream.isNotBlank()) {
                        idlixLog("PLAYBACK_RESULT", "source=redeem emitted=true")
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
                                if (java.net.URI(stream).host == "e2e.majorplay.net") startProxy(stream, mainUrl) else stream,
                                type = ExtractorLinkType.M3U8
                            ) {
                                referer = "$mainUrl/"
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl
                        )

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
                idlixLog("INITIAL_RESULT", "emitted=true")
                callback(
                    newExtractorLink(
                        "IDLIX",
                        "IDLIX",
                        initial,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = "$mainUrl/"
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36",
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl
                        )

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
            val result = JSONObject(response.text)
        idlixLog("CLAIM_RESPONSE", "kind=${result.optString("kind")} hasClaim=${result.optString("claim").isNotBlank()} hasRedeem=${result.optString("redeemUrl").isNotBlank()} errorPresent=${result.has("error")}")
        return result
    }


}
