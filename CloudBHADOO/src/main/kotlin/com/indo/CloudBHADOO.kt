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

        val title = videoName
            .substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(
                Regex(
                    "(?i)\\b(19\\d{2}|20\\d{2}|2160p|1080p|720p|WEB-DL|WEBRip|BluRay|x264|x265)\\b.*"
                ),
                ""
            )
            .trim()

        if (title.isBlank()) return null

        val query = java.net.URLEncoder.encode(title, "UTF-8")
        val response = app.get(
            "https://api.subsource.net/api/v1/movies/search?q=$query&searchType=text",
            headers = mapOf("X-API-Key" to key)
        )

        val movies = org.json.JSONObject(response.text)
            .optJSONArray("data") ?: return null

        for (i in 0 until movies.length()) {
            val movie = movies.getJSONObject(i)
            if (movie.optString("title").equals(title, true)) {
                return movie.optInt("movieId").takeIf { it > 0 }
            }
        }

        return movies.optJSONObject(0)
            ?.optInt("movieId")
            ?.takeIf { it > 0 }
    }


    private suspend fun findSubsourceSubtitle(
        movieId: Int,
        videoName: String
    ): Int? {
        val key = getSubsourceKey()
        if (key.isBlank()) return null

        val response = app.get(
            "https://api.subsource.net/api/v1/subtitles?movieId=$movieId",
            headers = mapOf("X-API-Key" to key)
        )

        val subtitles = org.json.JSONObject(response.text)
            .optJSONArray("data") ?: return null

        fun normalize(value: String): String =
            value.lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()

        val video = normalize(videoName.substringBeforeLast("."))
        var bestId: Int? = null
        var bestScore = -1

        for (i in 0 until subtitles.length()) {
            val sub = subtitles.optJSONObject(i) ?: continue
            val language = sub.optString("language")

            if (!language.equals("Indonesian", true) &&
                !language.equals("id", true)) continue

            val id = sub.optInt("subtitleId")
            if (id <= 0) continue

            val releases = sub.optJSONArray("releaseInfo")
            var score = 0

            if (releases != null) {
                for (j in 0 until releases.length()) {
                    val release = normalize(releases.optString(j))
                    if (release == video) {
                        score = maxOf(score, 100)
                    } else {
                        val videoWords = video.split(" ").toSet()
                        val releaseWords = release.split(" ").toSet()
                        score = maxOf(
                            score,
                            videoWords.intersect(releaseWords).size
                        )
                    }
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestId = id
            }
        }

        return bestId
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

                        return@withContext android.net.Uri
                            .fromFile(file).toString()
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
                val subtitleId = findSubsourceSubtitle(
                    movieId, videoName
                )

                if (subtitleId != null) {
                    val subtitleUrl = downloadSubsourceSrt(subtitleId)

                    if (subtitleUrl != null) {
                        subtitleCallback(
                            SubtitleFile("Indonesian (Subsource)", subtitleUrl)
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
