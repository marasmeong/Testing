package com.indo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import kotlinx.coroutines.async

class CloudBHADOO(private val context: android.content.Context) : MainAPI() {
    private fun getTmdbToken(): String {
        return context.getSharedPreferences(
            "cloudbhadoo_tmdb",
            android.content.Context.MODE_PRIVATE
        ).getString("read_token", "")?.trim().orEmpty()
    }

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
        "1:/" to "Cloud BHADOO"
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

    private suspend fun getFiles(page: Int, path: String): Pair<List<VideoFile>, Boolean> {
        val response = app.post(
            "$mainUrl/$path",
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

            VideoFile(filename, path)
        }

        val hasNext = !json.isNull("nextPageToken")
        return result to hasNext
    }

    private data class DriveFolder(
        val name: String,
        val path: String
    )

    private suspend fun getFolders(): List<DriveFolder> {
        val result = mutableListOf<DriveFolder>()
        var page = 0

        do {
            val response = app.post(
                "$mainUrl/1:/",
                data = mapOf(
                    "page_token" to "",
                    "page_index" to page.toString()
                )
            )

            val json = org.json.JSONObject(decodeResponse(response.text))
            val files = json.getJSONObject("data").getJSONArray("files")

            for (i in 0 until files.length()) {
                val item = files.getJSONObject(i)
                val name = item.optString("name")
                val mime = item.optString("mimeType")

                if (mime == "application/vnd.google-apps.folder" &&
                    name !in setOf("Gambar", "Colab Notebooks")
                ) {
                    val encoded = URLEncoder.encode(name, "UTF-8")
                        .replace("+", "%20")

                    result.add(DriveFolder(name, "1:/$encoded/"))
                }
            }

            page++
            val hasNext = !json.isNull("nextPageToken")
        } while (hasNext && page < 20)

        return result
    }

    private fun fileUrl(filename: String, path: String): String {
        val encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20")
        return "$mainUrl/$path$encoded"
    }

    private fun title(filename: String): String =
        filename.substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tmdbTitle(filename: String): String {
        val clean = title(filename)
        return clean.split(
            Regex(
                """(?i)\\s+(?:19\\d{2}|20\\d{2}|2160p|1080p|720p|480p|WEB-DL|WEBRip|BluRay|BRRip|DVDRip|HDTV|x264|x265|HEVC|H264|H265|S\\d{1,2}E\\d{1,2})\\b"""
            ),
            limit = 2
        ).first().trim()
    }



    private data class MediaInfo(
        val query: String,
        val displayTitle: String,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?
    ) {
        val isEpisode: Boolean
            get() = season != null && episode != null
    }

    private fun parseMedia(filename: String): MediaInfo {
        val clean = title(filename)
            .replace(Regex("""(?i)\b(?:19|20)\d{2}\b""")) { it.value }
            .replace(Regex("""\s+"""), " ")
            .trim()

        val episodeMatch = Regex(
            """(?i)\bS(\d{1,2})E(\d{1,3})\b"""
        ).find(clean)

        val quality = Regex(
            """(?i)\b(?:2160p|1080p|720p|480p|WEB[- ]?DL|WEBRip|BluRay|BRRip|DVDRip|HDTV|x264|x265|HEVC|H264|H265)\b"""
        )

        if (episodeMatch != null) {
            val series = clean.substring(
                0, episodeMatch.range.first
            ).trim()

            val season = episodeMatch.groupValues[1].toInt()
            val episode = episodeMatch.groupValues[2].toInt()

            val remainder = clean.substring(
                episodeMatch.range.last + 1
            ).trim()

            val episodeTitle = remainder
                .split(Regex("""(?i)\b(?:Blu[ -]?Ray|WEB[- ]?DL|WEB[ -]?Rip|HD[ -]?Rip|BRRip|DVDRip|HDTV|2160p|1080p|720p|480p|x264|x265|HEVC|H264|H265)\b"""), limit = 2)
                .first()
                .trim(' ', '-', '.')

            val code = "S%02dE%02d".format(
                java.util.Locale.ROOT, season, episode
            )

            return MediaInfo(
                query = series,
                displayTitle = listOf(
                    code, episodeTitle
                ).filter { it.isNotBlank() }.joinToString(" "),
                year = null,
                season = season,
                episode = episode,
                episodeTitle = episodeTitle.takeIf { it.isNotBlank() }
            )
        }

        val yearMatch = Regex(
            """\b(?:19|20)\d{2}\b"""
        ).find(clean)

        val movieTitle = if (yearMatch != null) {
            clean.substring(0, yearMatch.range.first).trim()
        } else {
            clean.split(quality, limit = 2).first()
                .replace(
                    Regex("""(?i)\b(?:NF|AMZN|DSNP|WEB|HDRip)\b.*"""),
                    ""
                )
                .trim()
        }

        val year = yearMatch?.value?.toIntOrNull()

        return MediaInfo(
            query = movieTitle,
            displayTitle = listOfNotNull(
                movieTitle,
                year?.toString()
            ).joinToString(" "),
            year = year,
            season = null,
            episode = null,
            episodeTitle = null
        )
    }

    private data class TmdbMovie(
        val title: String,
        val poster: String?,
        val backdrop: String?,
        val overview: String,
        val rating: Double,
        val year: Int?,
        val genres: List<String>
    )

    private val tmdbCache = java.util.concurrent.ConcurrentHashMap<String, TmdbMovie>()

    private suspend fun getTmdbMovie(filename: String): TmdbMovie? {
        val token = getTmdbToken()
        if (token.isBlank()) {
            return null
        }

        val media = parseMedia(filename)
        if (media.query.isBlank()) return null

        val cacheKey = "${media.query}:${media.year}:${media.season}:${media.episode}"
        tmdbCache[cacheKey]?.let { return it }

        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/json"
        )

        suspend fun fetch(path: String, language: String): org.json.JSONObject {
            val separator = if ('?' in path) "&" else "?"
            val response = app.get(
                "https://api.themoviedb.org/3/$path${separator}language=$language",
                headers = headers
            )
            return org.json.JSONObject(response.text)
        }

        fun imageUrl(path: String?, size: String): String? =
            path?.takeIf { it.startsWith("/") }
                ?.let { "https://image.tmdb.org/t/p/$size$it" }

        return try {
            val kind = if (media.isEpisode) "tv" else "movie"
            val query = URLEncoder.encode(media.query, "UTF-8")
            val yearParam = media.year?.let {
                if (kind == "movie") "&year=$it" else ""
            }.orEmpty()

            val search = fetch(
                "search/$kind?query=$query$yearParam",
                "en-US"
            )

            val results = search.optJSONArray("results") ?: return null
            val candidate = results.optJSONObject(0) ?: return null
            val id = candidate.optInt("id")
            if (id <= 0) return null

            val detailPath = "$kind/$id"
            val detailId = fetch(detailPath, "id-ID")
            val detailEn = if (detailId.optString("overview").isBlank()) {
                fetch(detailPath, "en-US")
            } else null

            val episodePath = if (media.isEpisode) {
                "tv/$id/season/${media.season}/episode/${media.episode}"
            } else null

            val episodeId = episodePath?.let { fetch(it, "id-ID") }
            val episodeEn = if (
                episodePath != null &&
                episodeId?.optString("overview").isNullOrBlank()
            ) fetch(episodePath, "en-US") else null

            val overview = if (media.isEpisode) {
                episodeId?.optString("overview")
                    ?.takeIf { it.isNotBlank() }
                    ?: episodeEn?.optString("overview")
                        ?.takeIf { it.isNotBlank() }
                    ?: detailId.optString("overview")
                        .takeIf { it.isNotBlank() }
                    ?: (detailEn ?: fetch(detailPath, "en-US"))
                        .optString("overview")
            } else {
                detailId.optString("overview")
                    .takeIf { it.isNotBlank() }
                    ?: detailEn?.optString("overview").orEmpty()
            }


            val genres = mutableListOf<String>()
            val genreArray = detailId.optJSONArray("genres")
            if (genreArray != null) {
                for (i in 0 until genreArray.length()) {
                    val name = genreArray.optJSONObject(i)
                        ?.optString("name").orEmpty()
                    if (name.isNotBlank()) genres.add(name)
                }
            }

            val poster = if (media.isEpisode) {
                imageUrl(
                    episodeId?.optString("still_path"),
                    "w780"
                ) ?: imageUrl(
                    detailId.optString("backdrop_path"),
                    "w780"
                )
            } else {
                imageUrl(detailId.optString("poster_path"), "w500")
            }

            val backdrop = imageUrl(
                detailId.optString("backdrop_path"), "w1280"
            )

            val releaseDate = if (media.isEpisode) {
                episodeId?.optString("air_date").orEmpty()
            } else {
                detailId.optString("release_date")
            }

            val result = TmdbMovie(
                title = media.displayTitle,
                poster = poster,
                backdrop = backdrop,
                overview = overview,
                rating = if (media.isEpisode) {
                    episodeId?.optDouble("vote_average", 0.0) ?: 0.0
                } else {
                    detailId.optDouble("vote_average", 0.0)
                },
                year = releaseDate.take(4).toIntOrNull(),
                genres = genres
            )

            tmdbCache[cacheKey] = result


            result
        } catch (e: Exception) {
            null
        }
    }


    private fun detectQuality(filename: String): String {
        val clean = filename.substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')

        val hc = Regex("""(?i)\bHC\b""")
            .containsMatchIn(clean)

        val quality = when {
            Regex("""(?i)\bWEB[- ]?DL\b""")
                .containsMatchIn(clean) -> "WEB-DL"

            Regex("""(?i)\bWEB[ -]?Rip\b""")
                .containsMatchIn(clean) -> "WEBRip"

            Regex("""(?i)\bBlu[ -]?Ray\b""")
                .containsMatchIn(clean) -> "Blu-Ray"

            Regex("""(?i)\bHD[ -]?Rip\b""")
                .containsMatchIn(clean) -> "HDRip"

            else -> ""
        }

        return if (quality.isNotEmpty() && hc)
            "HC $quality"
        else quality
    }


    private fun displayTitleWithQuality(filename: String): String {
        val media = parseMedia(filename)
        val title = media.displayTitle

        if (media.isEpisode) {
            return title.replace(
                Regex("""(?i)\\s+(?:HC\\s+)?(?:Blu[ -]?Ray|WEB[- ]?DL|WEB[ -]?Rip|HD[ -]?Rip|BRRip|DVDRip|HDTV)(?=\\s|$).*"""),
                ""
            ).trim()
        }

        val quality = detectQuality(filename)
        return if (quality.isBlank()) title else "$title $quality"
    }

    private suspend fun toSearchResponse(file: VideoFile): SearchResponse {
        val metadata = getTmdbMovie(file.name)

        return newMovieSearchResponse(
            displayTitleWithQuality(file.name),
            fileUrl(file.name, file.id),
            TvType.Movie
        ) {
            posterUrl = metadata?.poster
                ?: "$mainUrl/1:/Gambar/logodua.jpg"
        }
    }

    private suspend fun toSearchResponses(
        files: List<VideoFile>
    ): List<SearchResponse> =
        kotlinx.coroutines.coroutineScope {
            files.map { file ->
                async {
                    toSearchResponse(file)
                }
            }.map { it.await() }
        }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data != "1:/") {
            val (files, hasNext) = getFiles(page, request.data)
            return newHomePageResponse(
                request.name,
                toSearchResponses(files),
                hasNext
            )
        }

        val categories = mutableListOf<HomePageList>()

        val (rootFiles, _) = getFiles(1, "1:/")
        if (rootFiles.isNotEmpty()) {
            categories.add(
                HomePageList(
                    "Semua Film",
                    toSearchResponses(rootFiles),
                    false
                )
            )
        }

        for (folder in getFolders()) {
            try {
                val (videos, _) = getFiles(1, folder.path)
                if (videos.isNotEmpty()) {
                    categories.add(
                        HomePageList(
                            folder.name,
                            toSearchResponses(videos),
                            false
                        )
                    )
                }
            } catch (e: Exception) {
            }
        }

        return newHomePageResponse(categories, false)
    }

    override suspend fun load(url: String): LoadResponse {
        val filename = java.net.URLDecoder.decode(
            url.substringAfterLast("/"), "UTF-8"
        )

        val metadata = getTmdbMovie(filename)
        val fallbackPoster = "$mainUrl/1:/Gambar/logodua.jpg"

        return newMovieLoadResponse(
            displayTitleWithQuality(filename),
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = metadata?.poster ?: fallbackPoster
            backgroundPosterUrl = metadata?.backdrop ?: fallbackPoster

            plot = metadata?.overview
                ?.takeIf { it.isNotBlank() }
                ?: "Sinopsis belum tersedia."

            year = metadata?.year

            metadata?.genres
                ?.takeIf { it.isNotEmpty() }
                ?.let { genreList ->
                    tags = genreList
                }

            metadata?.let {
                if (it.rating > 0.0) {
                    score = Score.from10(it.rating)
                }
            }
        }
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

        fun similarity(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0

            var previous = IntArray(b.length + 1) { it }

            for (i in a.indices) {
                val current = IntArray(b.length + 1)
                current[0] = i + 1

                for (j in b.indices) {
                    val cost = if (a[i] == b[j]) 0 else 1
                    current[j + 1] = minOf(
                        current[j] + 1,
                        previous[j + 1] + 1,
                        previous[j] + cost
                    )
                }

                previous = current
            }

            return 1.0 - previous[b.length].toDouble() /
                maxOf(a.length, b.length)
        }

        val filename = videoName.substringAfterLast("/")
        val cleaned = filename.substringBeforeLast(".")
            .replace('.', ' ')
            .replace('_', ' ')

        val yearMatch = Regex("\\b(?:19|20)\\d{2}\\b").find(cleaned)
        val year = yearMatch?.value?.toIntOrNull()

        val title = if (yearMatch != null) {
            cleaned.substring(0, yearMatch.range.first).trim()
        } else {
            cleaned.replace(
                Regex("(?i)\\b(?:2160p|1080p|720p|480p|WEB-DL|WEBRip|BluRay|x264|x265)\\b.*"),
                ""
            ).trim()
        }

        if (title.isBlank()) return null

        val words = title.split(Regex("\\s+"))
        val queries = linkedSetOf<String>()

        queries.add(filename)
        if (year != null) queries.add("$title $year")
        queries.add(title)
        if (words.size >= 2) {
            queries.add(words.take(2).joinToString(" "))
        }
        if (words.size >= 2) {
            queries.add(words.first())
        }

        val target = normalize(title)
        val targetWords = target.split(" ").toSet()

        var bestId: Int? = null
        var bestScore = -999

        val seen = mutableSetOf<Int>()

        for (query in queries) {
            try {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                val response = app.get(
                    "https://api.subsource.net/api/v1/movies/search?q=$encoded&searchType=text",
                    headers = mapOf("X-API-Key" to key)
                )

                val movies = org.json.JSONObject(response.text)
                    .optJSONArray("data") ?: continue

                for (i in 0 until movies.length()) {
                    val movie = movies.optJSONObject(i) ?: continue
                    val id = movie.optInt("movieId")
                    if (id <= 0 || !seen.add(id)) continue

                    val actual = normalize(movie.optString("title"))
                    if (actual.isBlank()) continue

                    val actualWords = actual.split(" ").toSet()
                    val union = targetWords.union(actualWords).size
                    val overlap = if (union == 0) 0.0 else
                        targetWords.intersect(actualWords).size.toDouble() / union

                    var score = if (actual == target) {
                        100
                    } else {
                        (overlap * 70).toInt()
                    }

                    if (actual.startsWith("$target ")) {
                        score = maxOf(score, 90)
                    }

                    // Fuzzy matching untuk judul salah ketik.
                    // Skor 100 tetap diprioritaskan untuk judul persis.
                    val fuzzy = similarity(target, actual)
                    if (fuzzy >= 0.85) {
                        score = maxOf(
                            score,
                            (fuzzy * 95).toInt()
                        )
                    }

                    val movieYear = movie.optInt("releaseYear")
                    if (year != null) {
                        if (movieYear == year) score += 10
                        else if (movieYear > 0) score -= 15
                    }

                    if (score > bestScore) {
                        bestScore = score
                        bestId = id
                    }
                }
            } catch (e: Exception) {
            }
        }


        return if (bestScore >= 55) bestId else null
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

        while (page <= 20) {
            val response = try {
                app.get(
                    "https://api.subsource.net/api/v1/subtitles?movieId=$movieId&page=$page",
                    headers = mapOf("X-API-Key" to key)
                )
            } catch (e: Exception) {
                break
            }

            val json = org.json.JSONObject(response.text)
            val data = json.optJSONArray("data") ?: break

            for (i in 0 until data.length()) {
                subtitles.put(data.get(i))
            }

            val pagination = json.optJSONObject("pagination")
            val pages = pagination?.optInt("pages", 1) ?: 1

            if (page >= pages || data.length() == 0) break
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
                                    }
                                }
                            }.apply {
                                isDaemon = true
                                start()
                            }
                        } catch (e: Exception) {
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
                    SubtitleFile(
                        language,
                        fileUrl(
                            filename,
                            if (data.contains("/1:/GDFlix/")) "1:/GDFlix/" else "1:/"
                        )
                    )
                )
            }
        } catch (e: Exception) {
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
                    }
                }
            }
        } catch (e: Exception) {
        }

        return true
    }
}
