package com.indo

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

class MajorPlayExtractor : ExtractorApi() {
    override val name = "MajorPlay"
    override val mainUrl = "https://e2e.majorplay.net"
    override val requiresReferer = false

    companion object {
        private val sharedProxy = MajorPlayProxy()
    }


    private fun resolveUrl(base: String, path: String): String {
        return java.net.URI(base).resolve(path).toString()
    }



    private fun addToken(url: String, token: String): String {
        if (token.isBlank()) return url

        val uri = java.net.URI(url)
        val existing = uri.rawQuery.orEmpty()
            .split("&")
            .filter { it.isNotBlank() }
            .map { it.substringBefore("=") }
            .toSet()

        val missing = token.split("&")
            .filter { it.isNotBlank() }
            .filter { it.substringBefore("=") !in existing }

        if (missing.isEmpty()) return url

        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        val base = url.substringBefore("#")
        val separator = if ("?" in base) {
            if (base.endsWith("?") || base.endsWith("&")) "" else "&"
        } else "?"

        return base + separator + missing.joinToString("&") + fragment
    }

    private fun parseMasterPlaylist(
        playlist: String,
        masterUrl: String
    ): Pair<String?, String?> {
        val token = masterUrl.substringAfter("?", "")
        val lines = playlist.lines()

        val videoPath = lines.firstOrNull {
            it.isNotBlank() && !it.startsWith("#")
        }

        val audioPath = Regex("""#EXT-X-MEDIA:TYPE=AUDIO[^\r\n]*URI="([^"]+)"""")
            .find(playlist)?.groupValues?.get(1)

        val video = videoPath?.let {
            addToken(resolveUrl(masterUrl, it), token)
        }

        val audio = audioPath?.let {
            addToken(resolveUrl(masterUrl, it), token)
        }

        return video to audio
    }



    private fun rewriteMediaPlaylist(
        playlist: String,
        playlistUrl: String
    ): String {
        val token = playlistUrl.substringAfter("?", "")

        fun proxyUrl(path: String): String {
            val original = addToken(
                resolveUrl(playlistUrl, path),
                token
            )
            return sharedProxy.registerSegment(original)
        }

        return playlist.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    Regex("""URI="([^"]+)"""").replace(line) { match ->
                        """URI="${proxyUrl(match.groupValues[1])}""""
                    }
                }

                line.isNotBlank() && !line.startsWith("#") -> {
                    proxyUrl(line)
                }

                else -> line
            }
        }
    }

    private suspend fun emitHlsLink(
        videoUrl: String,
        audioUrl: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val link = newExtractorLink(
            "MajorPlay",
            "MajorPlay",
            videoUrl,
            ExtractorLinkType.M3U8
        ) {
            if (audioUrl != null) {
                // Audio playlist sudah ditangani oleh master HLS lokal
            }
        }
        callback(link)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {

        val response = app.get(url, referer = referer)
        val playlist = response.text

        if (!playlist.startsWith("#EXTM3U")) return

        val (videoUrl, audioUrl) = parseMasterPlaylist(
            playlist,
            url
        )

        if (videoUrl == null || audioUrl == null) return

        val videoResponse = app.get(videoUrl, referer = referer)
        val audioResponse = app.get(audioUrl, referer = referer)

        if (!videoResponse.text.startsWith("#EXTM3U")) return
        if (!audioResponse.text.startsWith("#EXTM3U")) return

        val videoPlaylist = rewriteMediaPlaylist(
            videoResponse.text, videoUrl
        )
        val audioPlaylist = rewriteMediaPlaylist(
            audioResponse.text, audioUrl
        )


        val proxyMaster = playlist.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#EXT-X-MEDIA:") &&
                    line.contains("TYPE=AUDIO") -> {
                    Regex("""URI="([^"]+)"""").replace(line) {
                        """URI="audio.m3u8" """
                            .trimEnd()
                    }
                }

                line.isNotBlank() && !line.startsWith("#") -> {
                    "video.m3u8"
                }

                else -> line
            }
        }

        val proxy = sharedProxy
        proxy.setMasterPlaylist(proxyMaster)
        proxy.setMediaPlaylists(videoPlaylist, audioPlaylist)
        proxy.start()

        emitHlsLink(
            "http://127.0.0.1:18765/master.m3u8",
            null,
            callback
        )

        // Berikutnya: menangani playlist media dan playback.

    }
}
