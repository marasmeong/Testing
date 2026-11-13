package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class Otakudesu(
    private val context: android.content.Context
) : MainAPI() {

    private fun getTmdbToken(): String {
        return context.getSharedPreferences(
            "otakudesu_tmdb",
            android.content.Context.MODE_PRIVATE
        ).getString("read_token", "")?.trim().orEmpty()
    }

    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    private val ua = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ongoing-anime/page/" to "Anime Ongoing",
        "$mainUrl/complete-anime/page/" to "Anime Completed"
    )

    private val tmdbMetadata by lazy {
        OtakudesuTmdb(::getTmdbToken)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, headers = ua).document
        val home = coroutineScope {
            val limiter = Semaphore(4)
            document.select("div.venz > ul > li").map { el ->
                async {
                    limiter.withPermit {
                        val a = el.selectFirst("a[href]")
                            ?: return@withPermit null
                        val href = a.attr("href").ifBlank { null }
                            ?: return@withPermit null
                        val title = el.selectFirst("h2.jdlflm")
                            ?.text()?.trim()
                            ?: a.attr("title").ifBlank { null }
                            ?: return@withPermit null
                        val poster = el.selectFirst("div.thumbz > img")
                            ?.attr("src")?.ifBlank { null }
                        val epText = el.selectFirst("div.epz")
                            ?.text()?.trim() ?: ""
                        val epNum = Regex("(\\d+)")
                            .find(epText)?.groupValues
                            ?.getOrNull(1)?.toIntOrNull()

                        val tmdb = tmdbMetadata.find(title)
                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = tmdb?.poster
                            addSub(epNum)
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        return newHomePageResponse(request.name, home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query&post_type=anime", headers = ua).document
        return coroutineScope {
            val limiter = Semaphore(4)
            document.select("ul.chivsrc > li").map { el ->
                async {
                    limiter.withPermit {
                        val a = el.selectFirst("h2 > a")
                            ?: return@withPermit null
                        val href = a.attr("href").ifBlank { null }
                            ?: return@withPermit null
                        val title = a.text().trim().ifBlank { null }
                            ?: return@withPermit null

                        val tmdb = tmdbMetadata.find(title)
                        newAnimeSearchResponse(title, href, TvType.Anime) {
                            this.posterUrl = tmdb?.poster
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = ua).document

        val title = document.selectFirst("div.infozingle > p:nth-child(1) > span")
            ?.ownText()?.replace(":", "")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val statusText = document.selectFirst("div.infozingle > p:nth-child(6) > span")
            ?.ownText()?.replace(":", "")?.trim() ?: ""
        val status = if (statusText.contains("Ongoing", true)) ShowStatus.Ongoing else ShowStatus.Completed
        val year = Regex("\\d, (\\d*)").find(
            document.select("div.infozingle > p:nth-child(9) > span").text()
        )?.groupValues?.get(1)?.toIntOrNull()

        // Episode list — Otakudesu: div.episodelist[1] (index 1 = daftar episode, bukan batch)
        val episodeLists = document.select("div.episodelist")
        val episodeList = if (episodeLists.size > 1) episodeLists[1] else episodeLists.firstOrNull()
        val episodes = episodeList?.select("ul > li")?.mapNotNull { li ->
            val a = li.selectFirst("a") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val name = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            val ep = Regex("Episode\\s?(\\d+)", RegexOption.IGNORE_CASE).find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(href) { this.name = name; this.episode = ep }
        }?.reversed() ?: emptyList()

        val tmdb = tmdbMetadata.find(title, year)

        return newAnimeLoadResponse(
            tmdb?.title ?: title,
            url,
            TvType.Anime
        ) {
            engName = title
            posterUrl = tmdb?.poster
            backgroundPosterUrl = tmdb?.backdrop
            this.year = tmdb?.year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = status
            plot = tmdb?.overview ?: ""
            this.tags = tmdb?.genres ?: emptyList()
        }
    }

    private suspend fun resolveDesuLink(url: String): String? {
        return try {
            val resp = app.get(url, headers = ua, allowRedirects = false)
            resp.headers["location"]?.ifBlank { null }
        } catch (e: Exception) { null }
    }

    private suspend fun loadMirrors(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(data, headers = ua)
            val html = res.text
            val ajax = "$mainUrl/wp-admin/admin-ajax.php"

            val nonceAction = Regex("""action:"([a-f0-9]{32})"\}\}\)\.done\(\(\{data:\w\}\)=>\{win""")
                .find(html)?.groupValues?.get(1) ?: return
            val embedAction = Regex("""action:"([a-f0-9]{32})"\}\}\)\.done\(\(\{data:\w\}\)=>\{doc""")
                .find(html)?.groupValues?.get(1) ?: return

            val nonce = JSONObject(
                app.post(ajax, headers = ua, referer = mainUrl, data = mapOf("action" to nonceAction)).text
            ).optString("data")
            if (nonce.isBlank()) return

            res.document.select("a[data-content]").forEach { a ->
                try {
                    val json = JSONObject(base64Decode(a.attr("data-content")))
                    val q = json.getString("q")
                    val label = a.text().trim().ifBlank { "Stream" }
                    val quality = q.filter { it.isDigit() }.toIntOrNull() ?: Qualities.Unknown.value

                    val enc = JSONObject(
                        app.post(
                            ajax, headers = ua, referer = mainUrl,
                            data = mapOf(
                                "id" to json.get("id").toString(),
                                "i" to json.get("i").toString(),
                                "q" to q,
                                "nonce" to nonce,
                                "action" to embedAction
                            )
                        ).text
                    ).optString("data")
                    if (enc.isBlank()) return@forEach

                    val src = Jsoup.parse(base64Decode(enc)).selectFirst("iframe")?.attr("src")
                        ?: return@forEach
                    if (src.contains("maintenance")) return@forEach
                    android.util.Log.d("OtakuMirror", "MIRROR=$label SRC=$src")

                    if (src.contains("desustream.net/dstream")) {
                        val video = app.get(src, headers = ua, referer = mainUrl).document
                            .selectFirst("source[src]")?.attr("src") ?: return@forEach
                        android.util.Log.d("OtakuMirror", "video=$video"); callback(newExtractorLink(name, "Stream $label", video) {
                            this.quality = quality
                            this.referer = src
                            this.headers = ua
                        })
                    } else {
                        loadExtractor(src, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, headers = ua).document
        loadMirrors(data, subtitleCallback, callback)

        document.select("div.download li").forEach { li ->
            val qualityText = li.selectFirst("strong")?.text() ?: ""
            val quality = when {
                qualityText.contains("1080") -> Qualities.P1080.value
                qualityText.contains("720")  -> Qualities.P720.value
                qualityText.contains("480")  -> Qualities.P480.value
                qualityText.contains("360")  -> Qualities.P360.value
                qualityText.contains("240")  -> Qualities.P240.value
                else -> Qualities.Unknown.value
            }

            li.select("a[href]").forEach { a ->
                val href = a.attr("href").ifBlank { null } ?: return@forEach
                val serverName = a.text().trim().ifBlank { "Download" }

                val realUrl = if (href.contains("link.desustream.com")) {
                    resolveDesuLink(href) ?: return@forEach
                } else href

                val fixedUrl = fixUrl(realUrl)
                val lowerUrl = fixedUrl.lowercase()

                // Hanya bypass extractor kalau memang link media langsung.
                // Pixeldrain/KrakenFiles biasanya butuh extractor (halaman, bukan file stream langsung).
                val isDirectMedia =
                    Regex("\\.(mp4|mkv|webm|m3u8)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(fixedUrl) ||
                    lowerUrl.contains("pixeldrain.com/api/file/")

                if (isDirectMedia) {
                    callback(newExtractorLink(name, serverName, fixedUrl) {
                        this.quality = quality
                        this.referer = data
                    })
                } else {
                    when {
                        lowerUrl.contains("pixeldrain.com") -> {
                            val pdId = Regex("pixeldrain\\.com/u/(\\w+)")
                                .find(fixedUrl)
                                ?.groupValues
                                ?.getOrNull(1)
                            if (pdId != null) {
                                callback(newExtractorLink("PixelDrain", serverName, "https://pixeldrain.com/api/file/$pdId") {
                                    this.quality = quality
                                    this.referer = fixedUrl
                                })
                            } else {
                                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                            }
                        }

                        lowerUrl.contains("krakenfiles.com") -> {
                            try {
                                val kfDoc = app.get(fixedUrl).document
                                val streamUrl = kfDoc
                                    .selectFirst("source[src*=krakencloud], source[type=video/mp4]")
                                    ?.attr("src")
                                    ?.ifBlank { null }

                                if (streamUrl != null) {
                                    callback(newExtractorLink("KrakenFiles", serverName, streamUrl) {
                                        this.quality = quality
                                        this.referer = fixedUrl
                                    })
                                } else {
                                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                                }
                            } catch (_: Exception) {
                                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                            }
                        }

                        lowerUrl.contains("filedon.co/") -> {
                        val slug = Regex("/(?:view|embed|d|f)/([A-Za-z0-9]+)")
                            .find(fixedUrl)?.groupValues?.getOrNull(1)
                        if (slug != null) {
                            val embedUrl = "https://filedon.co/embed/$slug"
                            try {
                                val doc = app.get(embedUrl).document
                                val raw = doc.selectFirst("[data-page]")?.attr("data-page")
                                val props = raw?.let {
                                    JSONObject(it).optJSONObject("props")
                                }
                                val direct = props?.optString("url")
                                    ?.takeIf { it.isNotBlank() && it != "null" }

                                if (direct != null) {
                                    callback(newExtractorLink("Filedon", serverName, direct) {
                                        this.quality = quality
                                        this.referer = embedUrl
                                    })
                                } else {
                                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                                }
                            } catch (_: Exception) {
                                loadExtractor(fixedUrl, data, subtitleCallback, callback)
                            }
                        } else {
                            loadExtractor(fixedUrl, data, subtitleCallback, callback)
                        }
                    }
                    else -> loadExtractor(fixedUrl, data, subtitleCallback) { link ->
                            if (link.name.contains("VidHidePro", ignoreCase = true) && quality != Qualities.Unknown.value) {
                                link.quality = quality
                            }
                            callback(link)
                        }
                    }
                }
            }
        }

        return true
    }

}
