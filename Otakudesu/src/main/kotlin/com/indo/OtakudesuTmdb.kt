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


    private fun tmdbSearchVariants(title: String): List<String> {
        val roman = listOf(
            "", "I", "II", "III", "IV", "V",
            "VI", "VII", "VIII", "IX", "X"
        )

        val seasonPattern = Regex(
            """(?i)\s+(?:season\s*|s)(\d{1,2}|I|II|III|IV|V|VI|VII|VIII|IX|X)$"""
        )
        val suffixPattern = Regex(
            """(?i)\s+(X|IX|VIII|VII|VI|IV|V|III|II|I)$"""
        )

        val cleaned = title.replace(
            Regex("""(?i)\s+(?:sub\s*indo|subtitle\s*indonesia)$"""),
            ""
        ).trim()

        val seasonMatch = seasonPattern.find(cleaned)
        val romanMatch = if (seasonMatch == null) {
            suffixPattern.find(cleaned)
        } else null

        val season = when {
            seasonMatch != null -> {
                val value = seasonMatch.groupValues[1].uppercase()
                value.toIntOrNull()
                    ?: roman.indexOf(value).takeIf { it > 0 }
            }
            romanMatch != null ->
                roman.indexOf(romanMatch.groupValues[1].uppercase())
                    .takeIf { it > 0 }
            else -> null
        }

        val base = when {
            seasonMatch != null ->
                cleaned.removeRange(seasonMatch.range).trim()
            romanMatch != null ->
                cleaned.removeRange(romanMatch.range).trim()
            else -> cleaned
        }

        return buildList {
            add(cleaned)
            if (season != null && season in 1..10 && base.isNotBlank()) {
                add("$base ${roman[season]}")
                add("$base Season $season")
                add("$base Season ${roman[season]}")
                add("$base S$season")
                add(base)
            }
        }.filter { it.isNotBlank() }.distinct()
    }

    suspend fun find(title: String, year: Int? = null): OtakudesuTmdbInfo? {
        if (tokenProvider().isBlank()) return null

        val key = "${title.lowercase().trim()}:$year"
        cache[key]?.let { return it }

        return try {
            val queries = tmdbSearchVariants(title)
            val paths = queries.flatMap { searchTitle ->
                val query = URLEncoder.encode(searchTitle, "UTF-8")
                listOf(
                    "search/tv?query=$query",
                    "search/movie?query=$query"
                )
            }

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


            // Pilih kandidat dari TMDB TV dan Movie.
            // Batasi pemeriksaan judul alternatif agar tidak terlalu lambat.
            val eligible = candidates
                .filter {
                    val type = it.optString("media_type")
                    type.isBlank() || type == "tv" || type == "movie"
                }
                .distinctBy {
                    "${it.optString("media_type")}:${it.optInt("id")}"
                }
                .take(30)

            fun normalize(value: String): String =
                value.lowercase().replace(Regex("[^a-z0-9]"), "")

            val romanNumbers = listOf(
                "", "I", "II", "III", "IV", "V",
                "VI", "VII", "VIII", "IX", "X"
            )

            fun seasonInfo(value: String): Pair<String, Int?> {
                val cleaned = value.trim()
                val pattern = Regex(
                    """(?i)\s+(?:(?:season\s*|s)(\d{1,2}|X|IX|VIII|VII|VI|IV|V|III|II|I)|(X|IX|VIII|VII|VI|IV|V|III|II|I))$"""
                )
                val match = pattern.find(cleaned)
                    ?: return normalize(cleaned) to null

                val number = match.groupValues[1]
                    .ifBlank { match.groupValues[2] }
                    .uppercase()

                val season = number.toIntOrNull()
                    ?: romanNumbers.indexOf(number).takeIf { it > 0 }

                return normalize(
                    cleaned.removeRange(match.range)
                ) to season
            }

            val (sourceBase, sourceSeason) = seasonInfo(title)

            suspend fun matchScore(item: JSONObject): Int {
                val type = item.optString("media_type")
                    .takeIf { it == "tv" || it == "movie" }
                    ?: if (item.has("first_air_date")) "tv" else "movie"

                val names = mutableListOf(
                    item.optString("name"),
                    item.optString("title"),
                    item.optString("original_name"),
                    item.optString("original_title")
                )

                // Cek judul utama sebelum meminta alternatif TMDB.
                val directMatch = names.any { candidate ->
                    val (base, season) = seasonInfo(candidate)
                    val sameBase = sourceBase.isNotBlank() &&
                        base.isNotBlank() &&
                        (sourceBase == base ||
                         (sourceBase.length >= 5 &&
                          base.length >= 5 &&
                          (sourceBase.contains(base) ||
                           base.contains(sourceBase))))

                    sameBase &&
                        (sourceSeason == null || season == null ||
                         sourceSeason == season)
                }

                if (!directMatch) {
                    val id = item.optInt("id")
                    if (id > 0) {
                        try {
                            val alt = request("$type/$id/alternative_titles")
                            val entries = alt.optJSONArray(
                                if (type == "tv") "results" else "titles"
                            )
                            for (i in 0 until (entries?.length() ?: 0)) {
                                names.add(
                                    entries?.optJSONObject(i)
                                        ?.optString("title").orEmpty()
                                )
                            }
                        } catch (_: Exception) {}
                    }
                }

                val titleScore = names.maxOfOrNull { candidate ->
                    val (candidateBase, candidateSeason) =
                        seasonInfo(candidate)

                    val baseScore = when {
                        sourceBase.isBlank() || candidateBase.isBlank() -> 0
                        sourceBase == candidateBase -> 100
                        sourceBase.length >= 5 &&
                            candidateBase.length >= 5 &&
                            (sourceBase.contains(candidateBase) ||
                             candidateBase.contains(sourceBase)) -> 60
                        else -> 0
                    }

                    when {
                        baseScore == 0 -> 0
                        sourceSeason != null &&
                            candidateSeason != null &&
                            sourceSeason != candidateSeason -> 0
                        sourceSeason != null &&
                            candidateSeason == sourceSeason -> baseScore + 30
                        sourceSeason != null &&
                            candidateSeason == null -> baseScore
                        sourceSeason == null &&
                            candidateSeason != null -> 0
                        else -> baseScore
                    }
                } ?: 0

                if (titleScore == 0) return 0

                val release = item.optString(
                    "first_air_date",
                    item.optString("release_date")
                )
                val candidateYear = release.take(4).toIntOrNull()
                val yearScore =
                    if (year != null && candidateYear == year) 20 else 0

                return titleScore + yearScore
            }

            var best: JSONObject? = null
            var bestScore = 0

            for (item in eligible) {
                val score = matchScore(item)
                if (score > bestScore) {
                    bestScore = score
                    best = item
                }
            }

            best = best ?: return null

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
