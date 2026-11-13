package com.indo

import android.content.Context
import com.lagradost.cloudstream3.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope

class MeongCloud(context: Context) : MainAPI() {

    override var name = "Meong Cloud"
    override var mainUrl = "https://anichin.moe"
    override var lang = "id"

    override val hasMainPage = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.TvSeries,
        TvType.Movie,
        TvType.OVA
    )

    // Lima provider asli
    private val sources: List<MainAPI> = listOf(
        Anichin(),
        AnimeXin(),
        CloudBHADOO(context.applicationContext),
        Otakudesu(),
        Sokuja()
    )

    // Kategori homepage asli dari lima provider
    override val mainPage by lazy {
        sources.flatMapIndexed { index, source ->
            source.mainPage.mapIndexed { category, original ->
                MainPageData(
                    "${source.name} — ${original.name}",
                    "meong:$index:$category",
                    original.horizontalImages
                )
            }
        }
    }

    // Mengambil homepage langsung dari provider asal
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val parts = request.data.split(":")
        val index = parts.getOrNull(1)?.toIntOrNull()
            ?: return newHomePageResponse(emptyList())

        val category = parts.getOrNull(2)?.toIntOrNull()
            ?: return newHomePageResponse(emptyList())

        val source = sources.getOrNull(index)
            ?: return newHomePageResponse(emptyList())

        val original = source.mainPage.getOrNull(category)
            ?: return newHomePageResponse(emptyList())

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
                section.list.map { item ->
                    when (item) {
                        is AnimeSearchResponse ->
                            item.copy(apiName = name)
                        is MovieSearchResponse ->
                            item.copy(apiName = name)
                        else -> item
                    }
                },
                section.isHorizontalImages
            )
        }

        return newHomePageResponse(sections, response.hasNext)
    }

    // Pencarian menggunakan fungsi asli setiap provider
    override suspend fun search(query: String): List<SearchResponse> =
        supervisorScope {
            sources.filterNot { it is CloudBHADOO }.map { source ->
                async {
                    try {
                        source.search(query).orEmpty().map { item ->
                            when (item) {
                                is AnimeSearchResponse ->
                                    item.copy(apiName = name)
                                is MovieSearchResponse ->
                                    item.copy(apiName = name)
                                else -> item
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(
                            "MeongCloud",
                            "Search gagal: ${source.name}",
                            e
                        )
                        emptyList<SearchResponse>()
                    }
                }
            }.awaitAll().flatten()
                .distinctBy { it.url }
        }


    // Menentukan provider berdasarkan URL asli
    private fun sourceFor(url: String): Pair<Int, MainAPI> {
        val index = sources.indexOfFirst { source ->
            val host = try {
                java.net.URI(source.mainUrl).host.orEmpty()
            } catch (_: Exception) {
                ""
            }

            host.isNotBlank() &&
                url.contains(host, ignoreCase = true)
        }

        if (index < 0) {
            throw ErrorLoadingException(
                "Provider asal tidak ditemukan: $url"
            )
        }

        return index to sources[index]
    }

    // Menandai episode sesuai provider asal
    private fun pack(index: Int, data: String): String {
        val encoded = android.util.Base64.encodeToString(
            data.toByteArray(Charsets.UTF_8),
            android.util.Base64.NO_WRAP or
                android.util.Base64.URL_SAFE
        )
        return "meong:$index:$encoded"
    }

    private fun unpack(data: String): Pair<Int, String>? {
        if (!data.startsWith("meong:")) return null

        val parts = data.split(":", limit = 3)
        if (parts.size != 3) return null

        val index = parts[1].toIntOrNull() ?: return null
        if (index !in sources.indices) return null

        val decoded = try {
            String(
                android.util.Base64.decode(
                    parts[2],
                    android.util.Base64.URL_SAFE
                ),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            return null
        }

        return index to decoded
    }

    // Detail film menggunakan fungsi asli provider
    override suspend fun load(url: String): LoadResponse {
        val (index, source) = sourceFor(url)

        val result = source.load(url)
            ?: throw ErrorLoadingException(
                "Gagal memuat detail: $url"
            )

        when (result) {
            is MovieLoadResponse -> {
                result.dataUrl = pack(index, result.dataUrl)
            }

            is TvSeriesLoadResponse -> {
                result.episodes.forEach { episode ->
                    episode.data = pack(index, episode.data)
                }
            }

            is AnimeLoadResponse -> {
                result.episodes.values.flatten().forEach { episode ->
                    episode.data = pack(index, episode.data)
                }
            }
        }

        result.apiName = name
        return result
    }

    // Player menggunakan loadLinks asli provider
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        val (index, original) = unpack(data) ?: return false

        return sources[index].loadLinks(
            original,
            isCasting,
            subtitleCallback,
            callback
        )
    }

    // Fungsi homepage, search, load, dan loadLinks
    // akan diteruskan ke provider asal.

}
