package com.indo

import android.app.AlertDialog
import android.content.Context
import android.widget.EditText
import kotlinx.coroutines.launch
import com.lagradost.cloudstream3.actions.VideoClickAction
import com.lagradost.cloudstream3.ui.result.ResultEpisode
import com.lagradost.cloudstream3.ui.result.LinkLoadingResult
import com.lagradost.cloudstream3.utils.UiText

class SubsourceSearchAction : VideoClickAction() {

    override val name: UiText =
        UiText.DynamicString("Cari Subtitle Subsource")

    override val isPlayer: Boolean = false

    override fun shouldShow(
        context: Context?,
        episode: ResultEpisode?
    ): Boolean {
        return episode?.data?.contains(
            "naza.meong.workers.dev", ignoreCase = true
        ) == true
    }

    override suspend fun runAction(
        context: Context?,
        episode: ResultEpisode,
        result: LinkLoadingResult,
        index: Int?
    ) {
        val ctx = context ?: return
        uiThread {
            val input = EditText(ctx).apply {
                setSingleLine(true)
                setText(
                    episode.name.orEmpty()
                        .replace(Regex("(?i)\\b(?:2160p|1080p|720p|480p)\\b.*"), "")
                        .trim()
                )
                setSelectAllOnFocus(true)
                setPadding(40, 30, 40, 30)
            }

            AlertDialog.Builder(ctx)
                .setTitle("Cari Film di Subsource")
                .setMessage("Masukkan judul film yang ingin dicari.")
                .setView(input)
                .setPositiveButton("Cari") { _, _ ->
                    val query = input.text.toString().trim()
                    if (query.isNotBlank()) {
                        kotlinx.coroutines.CoroutineScope(
                            kotlinx.coroutines.Dispatchers.IO
                        ).launch {
                            try {
                                val api = CloudBHADOO(context.applicationContext)
                                val movies = api.searchSubsourceMoviesManual(query)

                                uiThread {
                                    if (movies.isEmpty()) {
                                        AlertDialog.Builder(ctx)
                                            .setTitle("Film tidak ditemukan")
                                            .setMessage("Coba judul yang lebih singkat.")
                                            .setPositiveButton("OK", null)
                                            .show()
                                    } else {
                                        val labels = movies.map {
                                            "${it.title} (${it.year})"
                                        }.toTypedArray()

                                        AlertDialog.Builder(ctx)
                                            .setTitle("Pilih Film Subsource")
                                            .setItems(labels) { _, selected ->
                                                val movie = movies[selected]

                                                kotlinx.coroutines.CoroutineScope(
                                                    kotlinx.coroutines.Dispatchers.IO
                                                ).launch {
                                                    try {
                                                        val subtitles = api.getManualSubtitles(
                                                            movie.id,
                                                            episode.name.orEmpty()
                                                        )

                                                        uiThread {
                                                            if (subtitles.isEmpty()) {
                                                                AlertDialog.Builder(ctx)
                                                                    .setTitle("Subtitle Indonesia tidak ditemukan")
                                                                    .setPositiveButton("OK", null)
                                                                    .show()
                                                            } else {
                                                                val subLabels = subtitles.map {
                                                                    it.release
                                                                }.toTypedArray()

                                                                AlertDialog.Builder(ctx)
                                                                    .setTitle("Subtitle Indonesia - ${movie.title}")
                                                                    .setItems(subLabels) { _, subIndex ->
                                                                        val chosen = subtitles[subIndex]

                                                                        kotlinx.coroutines.CoroutineScope(
                                                                            kotlinx.coroutines.Dispatchers.IO
                                                                        ).launch {
                                                                            try {
                                                                                val url = api.getManualSubtitleUrl(chosen.id)
                                                                                if (url == null) {
                                                                                    uiThread {
                                                                                        android.widget.Toast.makeText(
                                                                                            context,
                                                                                            "Gagal mengunduh subtitle",
                                                                                            android.widget.Toast.LENGTH_LONG
                                                                                        ).show()
                                                                                    }
                                                                                } else {
                                                                                    val subtitle = com.lagradost.cloudstream3.ui.player.SubtitleData(
                                                                                        "Indonesian | ${chosen.release}",
                                                                                        "",
                                                                                        url,
                                                                                        com.lagradost.cloudstream3.ui.player.SubtitleOrigin.URL,
                                                                                        "application/x-subrip",
                                                                                        emptyMap(),
                                                                                        "id"
                                                                                    )

                                                                                    android.util.Log.i(
                                                                                        "CloudBHADOO",
                                                                                        "Manual subtitle ready: ${subtitle.url}"
                                                                                    )

                                                                                    uiThread {
                                                                                        android.widget.Toast.makeText(
                                                                                            ctx,
                                                                                            "Subtitle berhasil diunduh. Integrasi player belum tersedia.",
                                                                                            android.widget.Toast.LENGTH_LONG
                                                                                        ).show()
                                                                                    }
                                                                                }
                                                                            } catch (e: Exception) {
                                                                                android.util.Log.e(
                                                                                    "CloudBHADOO",
                                                                                    "Manual subtitle download failed",
                                                                                    e
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                    .setNegativeButton("Batal", null)
                                                                    .show()
                                                            }
                                                        }
                                                    } catch (e: Exception) {
                                                        android.util.Log.e(
                                                            "CloudBHADOO",
                                                            "Manual subtitle list failed",
                                                            e
                                                        )
                                                    }
                                                }
                                            }
                                            .setNegativeButton("Batal", null)
                                            .show()
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e(
                                    "CloudBHADOO", "Manual search failed", e
                                )
                            }
                        }
                    }
                }
                .setNegativeButton("Batal", null)
                .show()
        }
    }
}
