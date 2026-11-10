package com.indo

import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import java.net.InetAddress

class MajorPlayProxy(
    private val port: Int = 18765
) {
    private var server: ServerSocket? = null

    @Volatile
    private var masterPlaylist: String? = null

    @Volatile
    private var videoPlaylist: String? = null

    @Volatile
    private var audioPlaylist: String? = null

    fun setMediaPlaylists(video: String, audio: String) {
        require(video.startsWith("#EXTM3U"))
        require(audio.startsWith("#EXTM3U"))
        videoPlaylist = video
        audioPlaylist = audio
    }

    fun setMasterPlaylist(playlist: String) {
        require(playlist.trimStart().startsWith("#EXTM3U")) {
            "Invalid HLS playlist"
        }
        masterPlaylist = playlist
    }

    fun start() {
        if (server != null) return

        server = ServerSocket(
            port,
            10,
            InetAddress.getByName("127.0.0.1")
        )

        println("MajorPlay proxy listening on port $port")

        thread(isDaemon = true, name = "MajorPlayProxy") {
            while (true) {
                val socket = try {
                    server?.accept() ?: break
                } catch (_: Exception) {
                    break
                }

                thread(isDaemon = true) {
                    handleClient(socket)
                }
            }
        }
    }



    private fun serveSegment(
        path: String,
        client: Socket
    ) {
        val id = path.substringAfter("/segment/")
            .substringBefore(".m4s")

        val url = segmentUrls[id]

        if (url == null) {
            client.getOutputStream().apply {
                write(
                    "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                flush()
            }
            return
        }

        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection

        try {
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            val status = connection.responseCode
            val output = client.getOutputStream()

            if (status != 200) {
                output.write(
                    "HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .toByteArray()
                )
                output.flush()
                return
            }

            val length = connection.contentLengthLong
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/octet-stream\r\n")
                if (length >= 0) {
                    append("Content-Length: $length\r\n")
                }
                append("Connection: close\r\n\r\n")
            }

            output.write(headers.toByteArray())
            connection.inputStream.use { input ->
                input.copyTo(output)
            }
            output.flush()
        } finally {
            connection.disconnect()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 5000

            val input = client.getInputStream().bufferedReader()
            val request = input.readLine() ?: return

            if (!request.startsWith("GET ")) return

            val path = request.split(" ").getOrNull(1) ?: return

            if (path.startsWith("/segment/")) {
                serveSegment(path, client)
                return
            }

            val playlist = when (path) {
                "/master.m3u8" -> masterPlaylist
                "/video.m3u8" -> videoPlaylist
                "/audio.m3u8" -> audioPlaylist
                else -> null
            }

            val isPlaylist = path.endsWith(".m3u8")
            val status = when {
                playlist != null -> "200 OK"
                isPlaylist -> "404 Not Found"
                path == "/" -> "200 OK"
                else -> "404 Not Found"
            }

            val body = playlist ?: if (path == "/") {
                "MajorPlay Proxy OK"
            } else {
                "Not Found"
            }

            val contentType = if (playlist != null) {
                "application/vnd.apple.mpegurl"
            } else {
                "text/plain; charset=utf-8"
            }

            val bytes = body.toByteArray(Charsets.UTF_8)

            val headers = (
                "HTTP/1.1 $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
            )

            val output = client.getOutputStream()
            output.write(headers.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        }
    }


    private val segmentUrls = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun registerSegment(url: String): String {
        val id = java.util.UUID.randomUUID().toString()
        segmentUrls[id] = url
        return "http://127.0.0.1:$port/segment/$id.m4s"
    }

    fun stop() {
        server?.close()
        server = null
    }
}
