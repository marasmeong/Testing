package com.indo

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** One CloudStream provider; each original provider remains a delegate. */
class MeongCloud(context: Context) : MainAPI() {
    override var name = "Meong Cloud"
    override var mainUrl = "https://anichin.moe"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie, TvType.OVA)

    private val sources: List<MainAPI> = listOf(
        Anichin(), AnimeXin(), CloudBHADOO(context), Otakudesu(), Sokuja()
    )

    override val mainPage by lazy {
        sources.flatMapIndexed { index, source ->
            source.mainPage.mapIndexed { category, original ->
                MainPageData(
                    "meong:$index:$category",
                    "${source.name} — ${original.name}",
                    original.horizontalImages
                )
            }
        }
    }

    private fun sourceFor(url: String): MainAPI = sources.firstOrNull { source ->
        url.startsWith(source.mainUrl.trimEnd('/'), ignoreCase = true)
    } ?: sources.firstOrNull { source ->
        url.contains(java.net.URI(source.mainUrl).host.orEmpty(), ignoreCase = true)
    } ?: throw ErrorLoadingException("Sumber tidak dikenali: $url")

    private fun pack(index: Int, data: String): String {
        val encoded = android.util.Base64.encodeToString(data.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        return "meong:$index:$encoded"
    }
    private fun unpack(data: String): Pair<Int, String>? {
        if (!data.startsWith("meong:")) return null
        val index = data.substringAfter("meong:").substringBefore(':').toIntOrNull() ?: return null
        if (index !in sources.indices) return null
        val decoded = try {
            String(android.util.Base64.decode(data.substringAfter("meong:$index:"), android.util.Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: Exception) { return null }
        return index to decoded
    }


    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val parts = request.data.split(":")
        val sourceIndex = parts.getOrNull(1)?.toIntOrNull()
            ?: throw ErrorLoadingException("Sumber tidak valid")
        val categoryIndex = parts.getOrNull(2)?.toIntOrNull()
            ?: throw ErrorLoadingException("Kategori tidak valid")

        val source = sources.getOrNull(sourceIndex)
            ?: throw ErrorLoadingException("Sumber tidak ditemukan")
        val original = source.mainPage.getOrNull(categoryIndex)
            ?: throw ErrorLoadingException("Kategori tidak ditemukan")

        val response = source.getMainPage(
            page,
            MainPageRequest(
                original.name,
                original.data,
                original.horizontalImages
            )
        ) ?: return newHomePageResponse(emptyList())

        val sections = response.items.map { section ->
            HomePageList(
                "${source.name} — ${section.name}",
                section.list,
                section.isHorizontalImages
            )
        }

        return newHomePageResponse(sections, response.hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        sources.map { source ->
            async { try { source.search(query) ?: emptyList<SearchResponse>() } catch (_: Exception) { emptyList<SearchResponse>() } }
        }.awaitAll().flatten().distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val source = sourceFor(url)
        val result = source.load(url) ?: throw ErrorLoadingException("Gagal memuat detail: $url")
        val index = sources.indexOf(source)
        // Keep original load response and all metadata; only tag playback data for routing.
        when (result) {
            is MovieLoadResponse -> result.dataUrl = pack(index, result.dataUrl)
            is TvSeriesLoadResponse -> result.episodes.forEach { it.data = pack(index, it.data) }
            is AnimeLoadResponse -> result.episodes.values.flatten().forEach { it.data = pack(index, it.data) }
        }
        result.apiName = name
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (index, original) = unpack(data) ?: return false
        return sources[index].loadLinks(original, isCasting, subtitleCallback, callback)
    }
}
