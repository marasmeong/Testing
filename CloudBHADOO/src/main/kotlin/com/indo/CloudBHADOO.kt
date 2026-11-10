package com.indo

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class CloudBHADOO : MainAPI() {
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
        return true
    }
}
