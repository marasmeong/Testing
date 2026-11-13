package com.indo

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal data class OtakudesuTmdbInfo(
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val overview: String?,
    val rating: Double?,
    val year: Int?,
    val genres: List<String>,
    val mediaType: String
)

internal class OtakudesuTmdb(
    private val tokenProvider: () -> String
) {
    private val cache = ConcurrentHashMap<String, OtakudesuTmdbInfo>()

    private fun image(path: String?, size: String): String? =
        path?.takeIf { it.startsWith("/") }
            ?.let { "https://image.tmdb.org/t/p/$size$it" }

    private suspend fun request(
        path: String,
        language: String = "en-US"
    ): JSONObject {
        val token = tokenProvider()
        require(token.isNotBlank())

        val separator = if ('?' in path) "&" else "?"
        val response = app.get(
            "https://api.themoviedb.org/3/$path${separator}language=$language",
            headers = mapOf(
                "Authorization" to "Bearer $token",
                "Accept" to "application/json"
            )
        )
        return JSONObject(response.text)
    }

    suspend fun find(title: String, year: Int? = null): OtakudesuTmdbInfo? {
        if (tokenProvider().isBlank()) return null

        val key = "${title.lowercase().trim()}:$year"
        cache[key]?.let { return it }

        return try {
            val query = URLEncoder.encode(title, "UTF-8")
            val paths = listOf(
                "search/multi?query=$query",
                "search/tv?query=$query",
                "search/movie?query=$query"
            )

            val candidates = paths.flatMap { path ->
                try {
                    val results = request(path).optJSONArray("results")
                    (0 until (results?.length() ?: 0)).mapNotNull { i ->
                        results?.optJSONObject(i)
                    }
                } catch (_: Exception) {
                    emptyList()
                }
            }

            val normalized = title.lowercase()
                .replace(Regex("[^a-z0-9]"), "")

            val best = candidates
                .filter {
                    val type = it.optString("media_type")
                    type.isBlank() || type == "tv" || type == "movie"
                }
                .maxByOrNull { item ->
                    val candidateTitle = item.optString(
                        "name",
                        item.optString("title")
                    )
                    val candidate = candidateTitle.lowercase()
                        .replace(Regex("[^a-z0-9]"), "")

                    val titleScore = when {
                        candidate == normalized -> 100
                        candidate.contains(normalized) ||
                            normalized.contains(candidate) -> 60
                        else -> 0
                    }

                    val release = item.optString(
                        "first_air_date",
                        item.optString("release_date")
                    )
                    val candidateYear = release.take(4).toIntOrNull()
                    val yearScore = if (
                        year != null && candidateYear == year
                    ) 20 else 0

                    titleScore + yearScore
                }?.takeIf { item ->
                    val names = listOf(
                        item.optString("name"),
                        item.optString("title"),
                        item.optString("original_name"),
                        item.optString("original_title")
                    )
                    names.any { candidate ->
                        val clean = candidate.lowercase()
                            .replace(Regex("[^a-z0-9]"), "")
                        normalized.isNotBlank() &&
                            clean.isNotBlank() &&
                            (clean == normalized ||
                             (normalized.length >= 5 &&
                              clean.length >= 5 &&
                              (clean.contains(normalized) ||
                               normalized.contains(clean))))
                    }
                } ?: return null

            val mediaType = best.optString("media_type")
                .takeIf { it == "tv" || it == "movie" }
                ?: if (best.has("first_air_date")) "tv" else "movie"

            val id = best.optInt("id")
            if (id <= 0) return null

            val details = request("$mediaType/$id")

            val genresArray = details.optJSONArray("genres")
            val genres = (0 until (genresArray?.length() ?: 0))
                .mapNotNull { i ->
                    genresArray?.optJSONObject(i)?.optString("name")
                        ?.takeIf { it.isNotBlank() }
                }

            val date = details.optString(
                "first_air_date",
                details.optString("release_date")
            )

            val info = OtakudesuTmdbInfo(
                title = details.optString(
                    "name",
                    details.optString("title", title)
                ),
                poster = image(details.optString("poster_path"), "w500"),
                backdrop = image(details.optString("backdrop_path"), "w1280"),
                overview = details.optString("overview")
                    .takeIf { it.isNotBlank() },
                rating = details.optDouble("vote_average")
                    .takeIf { !it.isNaN() },
                year = date.take(4).toIntOrNull(),
                genres = genres,
                mediaType = mediaType
            )

            cache[key] = info
            info
        } catch (_: Exception) {
            null
        }
    }
}
