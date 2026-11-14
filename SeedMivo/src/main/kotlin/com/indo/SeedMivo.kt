package com.indo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class SeedMivo : MainAPI() {
    override var mainUrl = "https://seedmivo.st"
    override var name = "SeedMivo"
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie)
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "$mainUrl/movies" to "Latest Added Movies"
    )

    private fun cards(doc: Document): List<SearchResponse> {
        return doc.select("a[href*='/movies/']")
            .mapNotNull { a ->
                val url = a.absUrl("href").substringBefore("#")
                if (!url.startsWith("$mainUrl/movies/")) return@mapNotNull null

                val img = a.selectFirst("img")
                val title = img?.attr("alt")?.takeIf { it.isNotBlank() }
                    ?: a.text().trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null

                newMovieSearchResponse(title, url, TvType.Movie) {
                    posterUrl = img?.absUrl("src")?.ifBlank { null }
                }
            }.distinctBy { it.url }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) request.data
                  else "${request.data}?page=$page"

        val items = cards(app.get(url).document)
        return newHomePageResponse(request.name, items, items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/").replace("-", " ")

        val poster = doc.selectFirst("meta[property=og:image]")
            ?.attr("content")

        val description = doc.selectFirst("meta[name=description]")
            ?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val html = doc.selectFirst(".hero[x-data]")?.attr("x-data")
            ?: return false

        val embed = Regex("""src:\s*'([^']+)'""")
            .find(html)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?: return false

        if (!embed.startsWith("https://")) return false

        return try {
            loadExtractor(embed, data, subtitleCallback, callback)
        } catch (_: Exception) {
            false
        }
    }
}
