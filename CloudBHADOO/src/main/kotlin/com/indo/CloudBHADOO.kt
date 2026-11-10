package com.indo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class CloudBHADOO(private val context: android.content.Context) : MainAPI() {
    private fun getSubsourceKey(): String {
        return context.getSharedPreferences(
            "cloudbhadoo_subsource",
            android.content.Context.MODE_PRIVATE
        ).getString("api_key", "")?.trim().orEmpty()
    }

    override var mainUrl = "https://naza.meong.workers.dev"
    override var name = "Cloud BHADOO"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "id"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "1:/" to "Semua Film"
    )

    private data class VideoFile(
        val name: String,
        val id: String
    )

    private fun decodeResponse(raw: String): String {
        val reversed = raw.reversed()
        val encoded = reversed.substring(24, reversed.length - 20)
        return String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private suspend fun getFiles(page: Int): Pair<List<VideoFile>, Boolean> {
        val response = app.post(
            "$mainUrl/1:/",
            data = mapOf(
                "page_token" to "",
                "page_index" to (page - 1).toString()
            )
        )

        val json = org.json.JSONObject(decodeResponse(response.text))
        val files = json.getJSONObject("data").getJSONArray("files")

        val result = (0 until files.length()).mapNotNull { i ->
            val file = files.getJSONObject(i)
            val filename = file.optString("name")
            val mime = file.optString("mimeType")

            if (!mime.startsWith("video/") &&
                !filename.endsWith(".mkv", true) &&
                !filename.endsWith(".mp4", true)
            ) return@mapNotNull null

            VideoFile(filename, file.optString("id"))
        }

        val hasNext = !json.isNull("nextPageToken")
        return result to hasNext
    }

    private fun fileUrl(filename: String): String {
        val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return "$mainUrl/1:/$encoded"
    }

    private fun title(filename: String): String =
        filename.substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun toSearchResponse(file: VideoFile): SearchResponse =
        newMovieSearchResponse(
            title(file.name),
            fileUrl(file.name),
            TvType.Movie
        )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val (files, hasNext) = getFiles(page)
        return newHomePageResponse(
            request.name,
            files.map(::toSearchResponse),
            hasNext
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val filename = java.net.URLDecoder.decode(
            url.substringAfterLast("/"), "UTF-8"
        )

        return newMovieLoadResponse(
            title(filename),
            url,
            TvType.Movie,
            url
        )
    }





    private suspend fun searchSubsourceMovie(
        videoName: String
    ): Int? {
        val key = getSubsourceKey()
        if (key.isBlank()) return null

        fun normalize(value: String): String =
            value.lowercase(java.util.Locale.ROOT)
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")

        val filename = videoName.substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')

        val yearMatch = Regex("\\b(?:19|20)\\d{2}\\b")
            .find(filename)

        val year = yearMatch?.value?.toIntOrNull()

        val cleanTitle = if (yearMatch != null) {
            filename.substring(0, yearMatch.range.first)
        } else {
            filename.replace(
                Regex(
                    "(?i)\\b(?:2160p|1080p|720p|480p|" +
                    "WEB-DL|WEBRip|BluRay|BDRip|HDRip|" +
                    "DVDRip|x264|x265|HEVC|AV1)\\b.*"
                ),
                ""
            )
        }.trim()

        val wanted = normalize(cleanTitle)
        if (wanted.isBlank()) return null

        val wantedWords = wanted.split(" ")
        val query = java.net.URLEncoder.encode(cleanTitle, "UTF-8")

        val response = app.get(
            "https://api.subsource.net/api/v1/movies/search" +
                "?q=$query&searchType=text",
            headers = mapOf("X-API-Key" to key)
        )

        val movies = org.json.JSONObject(response.text)
            .optJSONArray("data") ?: return null

        fun similarity(candidate: String): Double {
            val actual = normalize(candidate)
            if (actual.isBlank()) return 0.0
            if (actual == wanted) return 1.0

            val actualWords = actual.split(" ")

            // Judul pendek boleh menjadi awalan judul lengkap.
            // Minimal dua kata untuk menghindari kecocokan acak.
            val prefix = wantedWords.size >= 2 &&
                actualWords.size > wantedWords.size &&
                actualWords.take(wantedWords.size) == wantedWords

            if (prefix) return 0.94

            // Judul lengkap boleh muncul di awal nama alternatif.
            val reversePrefix = actualWords.size >= 2 &&
                wantedWords.size > actualWords.size &&
                wantedWords.take(actualWords.size) == actualWords

            if (reversePrefix) return 0.86

            val a = wantedWords.toSet()
            val b = actualWords.toSet()
            val common = a.intersect(b).size

            if (common == 0) return 0.0

            val precision = common.toDouble() / b.size
            val recall = common.toDouble() / a.size

            val f1 = if (precision + recall == 0.0) 0.0
                else 2 * precision * recall / (precision + recall)

            // Urutan kata membantu membedakan judul serupa.
            val sameOrder = wantedWords.filter { it in b } ==
                actualWords.filter { it in a }

            return if (sameOrder) f1 else f1 * 0.85
        }

        var bestId: Int? = null
        var bestScore = 0.0

        for (i in 0 until movies.length()) {
            val movie = movies.optJSONObject(i) ?: continue
            val id = movie.optInt("movieId")
            if (id <= 0) continue

            val titles = listOf(
                movie.optString("title"),
                movie.optString("alternateTitle")
            ).filter {
                it.isNotBlank() && it != "null"
            }

            val titleScore = titles.maxOfOrNull {
                similarity(it)
            } ?: 0.0

            val movieYear = movie.optInt("releaseYear")

            val yearAdjustment = when {
                year == null || movieYear == 0 -> 0.0
                year == movieYear -> 0.05
                kotlin.math.abs(year - movieYear) == 1 -> -0.05
                else -> -0.20
            }

            val score = titleScore + yearAdjustment

            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }

        android.util.Log.i(
            "CloudBHADOO",
            "Subsource match: $cleanTitle ($year), " +
                "id=$bestId, score=$bestScore"
        )

        return if (bestScore >= 0.75) bestId else null
    }

    private data class SubsourceChoice(
        val id: Int,
        val release: String,
        val score: Int
    )

    private suspend fun findSubsourceSubtitles(
        movieId: Int,
        videoName: String
    ): List<SubsourceChoice> {
        val key = getSubsourceKey()
        if (key.isBlank()) return emptyList()

        val subtitles = org.json.JSONArray()
        var page = 1

        while (page <= 100) {
            val response = app.get(
                "https://api.subsource.net/api/v1/subtitles?movieId=$movieId&page=$page",
                headers = mapOf("X-API-Key" to key)
            )

            val json = org.json.JSONObject(response.text)
            val items = json.optJSONArray("data") ?: break

            for (i in 0 until items.length()) {
                subtitles.put(items.get(i))
            }

            val totalPages = json.optJSONObject("pagination")
                ?.optInt("pages", 1) ?: 1

            if (page >= totalPages || items.length() == 0) break
            page++
        }

        fun normalize(value: String): String =
            value.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        val video = normalize(videoName.substringBeforeLast("."))
        val videoWords = video.split(" ").filter { it.isNotBlank() }.toSet()
        val results = mutableListOf<SubsourceChoice>()

        for (i in 0 until subtitles.length()) {
            val sub = subtitles.optJSONObject(i) ?: continue
            val language = sub.optString("language")

            if (!language.equals("Indonesian", true) &&
                !language.equals("id", true)) continue

            val id = sub.optInt("subtitleId")
            if (id <= 0) continue

            val releases = sub.optJSONArray("releaseInfo")
            val names = if (releases != null) {
                (0 until releases.length())
                    .map { releases.optString(it).trim() }
                    .filter { it.isNotBlank() }
            } else emptyList()

            val release = names.firstOrNull()
                ?: "Subtitle Indonesia #$id"

            val score = names.maxOfOrNull { name ->
                val normalized = normalize(name)
                if (normalized == video) 1000
                else videoWords.intersect(
                    normalized.split(" ").toSet()
                ).size
            } ?: 0

            results.add(SubsourceChoice(id, release, score))
        }

        return results
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<SubsourceChoice> { it.score }
                    .thenBy { it.release }
            )
    }


    private val subtitleServerLock = Any()
    private var subtitleServerPort = 0
    private val subtitleServerToken =
        java.util.UUID.randomUUID().toString().replace("-", "")

    private fun serveSubsourceSrt(file: java.io.File): String {
        require(file.isFile && file.length() > 0L) {
            "Subtitle SRT tidak tersedia"
        }

        synchronized(subtitleServerLock) {
            if (subtitleServerPort == 0) {
                val server = java.net.ServerSocket(
                    0,
                    50,
                    java.net.InetAddress.getByName("127.0.0.1")
                )
                subtitleServerPort = server.localPort

                Thread {
                    while (!server.isClosed) {
                        try {
                            val client = server.accept()
                            Thread {
                                client.use { socket ->
                                    socket.soTimeout = 10000
                                    try {
                                        val reader = socket.getInputStream()
                                            .bufferedReader()
                                        val request = reader.readLine().orEmpty()
                                        val path = request.split(" ")
                                            .getOrNull(1).orEmpty()

                                        val prefix = "/$subtitleServerToken/"
                                        val name = path.removePrefix(prefix)

                                        val validName = Regex(
                                            "cloudbhadoo_subsource_[0-9]+\\.srt"
                                        ).matches(name)

                                        val target = java.io.File(
                                            context.cacheDir, name
                                        )

                                        val valid = request.startsWith("GET ") &&
                                            path.startsWith(prefix) &&
                                            validName &&
                                            target.isFile &&
                                            target.length() in 1..5_000_000

                                        val output = socket.getOutputStream()

                                        if (valid) {
                                            val bytes = target.readBytes()
                                            val header =
                                                "HTTP/1.1 200 OK\r\n" +
                                                "Content-Type: application/x-subrip; charset=utf-8\r\n" +
                                                "Content-Length: ${bytes.size}\r\n" +
                                                "Access-Control-Allow-Origin: *\r\n" +
                                                "Connection: close\r\n\r\n"

                                            output.write(header.toByteArray())
                                            output.write(bytes)
                                        } else {
                                            output.write(
                                                ("HTTP/1.1 404 Not Found\r\n" +
                                                 "Content-Length: 0\r\n" +
                                                 "Connection: close\r\n\r\n")
                                                    .toByteArray()
                                            )
                                        }
                                        output.flush()
                                    } catch (e: Exception) {
                                        android.util.Log.w(
                                            "CloudBHADOO", "Subtitle HTTP error", e
                                        )
                                    }
                                }
                            }.apply {
                                isDaemon = true
                                start()
                            }
                        } catch (e: Exception) {
                            android.util.Log.w(
                                "CloudBHADOO", "Subtitle server stopped", e
                            )
                            break
                        }
                    }
                }.apply {
                    isDaemon = true
                    name = "CloudBHADOO-SubtitleServer"
                    start()
                }
            }

            return "http://127.0.0.1:$subtitleServerPort/" +
                "$subtitleServerToken/${file.name}"
        }
    }

    private suspend fun downloadSubsourceSrt(
        subtitleId: Int
    ): String? = kotlinx.coroutines.withContext(
        kotlinx.coroutines.Dispatchers.IO
    ) {
        val key = getSubsourceKey()
        if (key.isBlank()) return@withContext null

        val connection = (
            java.net.URL(
                "https://api.subsource.net/api/v1/subtitles/$subtitleId/download"
            ).openConnection() as java.net.HttpURLConnection
        )

        connection.setRequestProperty("X-API-Key", key)
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        try {
            if (connection.responseCode != 200) {
                return@withContext null
            }

            java.util.zip.ZipInputStream(
                connection.inputStream.buffered()
            ).use { zip ->
                var entry = zip.nextEntry

                while (entry != null) {
                    if (!entry.isDirectory &&
                        entry.name.endsWith(".srt", true)
                    ) {
                        val file = java.io.File(
                            context.cacheDir,
                            "cloudbhadoo_subsource_$subtitleId.srt"
                        )

                        file.outputStream().use { output ->
                            val buffer = ByteArray(8192)
                            var total = 0L

                            while (true) {
                                val count = zip.read(buffer)
                                if (count <= 0) break

                                total += count
                                if (total > 5_000_000) {
                                    throw java.io.IOException(
                                        "Subtitle exceeds 5 MB"
                                    )
                                }

                                output.write(buffer, 0, count)
                            }
                        }

                        android.util.Log.i(
                            "CloudBHADOO",
                            "Subsource SRT downloaded: ${file.name}, size=${file.length()} bytes"
                        )
                        if (!file.exists() || file.length() == 0L) {
                            throw java.io.IOException("Extracted SRT is empty")
                        }
                        return@withContext serveSubsourceSrt(file)
                    }

                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            null
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        callback(
            newExtractorLink(
                "Cloud BHADOO",
                "Google Drive MKV",
                data
            )
        )

        try {
            val videoName = java.net.URLDecoder.decode(
                data.substringAfterLast("/"), "UTF-8"
            )
            val response = app.post(
                "$mainUrl/1:/",
                data = mapOf("page_token" to "", "page_index" to "0")
            )
            val json = org.json.JSONObject(decodeResponse(response.text))
            val files = json.getJSONObject("data").getJSONArray("files")

            fun normalize(name: String): String =
                name.substringBeforeLast(".")
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), " ")
                    .trim()

            val videoTitle = normalize(videoName)
            val videoWords = videoTitle.split(" ")
            val matches = mutableListOf<Pair<Int, String>>()

            for (i in 0 until files.length()) {
                val filename = files.getJSONObject(i).optString("name")
                if (!listOf(".srt", ".ass", ".vtt").any {
                        filename.endsWith(it, true)
                    }) continue

                val subTitle = normalize(filename)
                    .replace(Regex("\\b(indonesia|indo|english|eng|subtitle|sub)\\b"), "")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                if (subTitle.length < 4) continue

                val subWords = subTitle.split(" ")
                val exact = subTitle == videoTitle
                val prefix = videoTitle.startsWith("$subTitle ")
                val firstWord = subWords.size == 1 &&
                    videoWords.firstOrNull() == subWords.first() &&
                    subTitle.length >= 5

                if (exact || prefix || firstWord) {
                    val score = if (exact) 3 else if (prefix) 2 else 1
                    matches.add(score to filename)
                }
            }

            val bestScore = matches.maxOfOrNull { it.first }
            matches.filter { it.first == bestScore }.forEach { (_, filename) ->
                val language = if (
                    filename.contains("english", true) ||
                    filename.contains(".eng.", true)
                ) "English" else "Indonesian"

                subtitleCallback(
                    SubtitleFile(language, fileUrl(filename))
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("CloudBHADOO", "Subtitle detection failed", e)
        }

        try {
            val videoName = java.net.URLDecoder.decode(
                data.substringAfterLast("/"), "UTF-8"
            )

            val movieId = searchSubsourceMovie(videoName)

            if (movieId != null) {
                val choices = findSubsourceSubtitles(movieId, videoName)

                for (choice in choices) {
                    try {
                        val subtitleUrl = downloadSubsourceSrt(choice.id)
                            ?: continue

                        subtitleCallback(
                            SubtitleFile(
                                "Indonesian | ${choice.release.take(100)}",
                                subtitleUrl
                            )
                        )
                    } catch (e: Exception) {
                        android.util.Log.w(
                            "CloudBHADOO",
                            "Subtitle ${choice.id} failed",
                            e
                        )
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(
                "CloudBHADOO",
                "Subsource subtitle failed",
                e
            )
        }

        return true
    }
}
