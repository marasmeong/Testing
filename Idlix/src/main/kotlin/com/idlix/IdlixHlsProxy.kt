package com.idlix

import java.net.ServerSocket
import java.net.InetAddress
import java.net.Socket
import kotlin.concurrent.thread

internal object IdlixHlsProxy {
    private var server: ServerSocket? = null
    private val authByVideo =
        java.util.concurrent.ConcurrentHashMap<String, String>()

    @Synchronized
    fun setMasterUrl(url: String) {
        val uri = java.net.URI(url)
        require(uri.scheme == "https")
        require(uri.host == "e2e.majorplay.net")

        val path = uri.rawPath ?: error("Invalid video path")
        val videoPath = path.substringBeforeLast("/") + "/"

        authByVideo[videoPath] = uri.rawQuery.orEmpty()
    }

    @Synchronized
    fun start(): Int {
        server?.takeIf { !it.isClosed }?.let {
            return it.localPort
        }

        val socket = ServerSocket(
            0,
            8,
            InetAddress.getByName("127.0.0.1")
        )
        server = socket

        thread(name = "Idlix-HLS-Proxy", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    thread(isDaemon = true) {
                        handleClient(client)
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }

        return socket.localPort
    }



    private fun rewriteManifest(
        manifest: String,
        upstreamUrl: String,
        localPort: Int
    ): String {
        val base = java.net.URI(upstreamUrl)
        val localBase = "http://127.0.0.1:$localPort"

        fun rewrite(reference: String): String {
            val resolved = base.resolve(reference)
            if (resolved.scheme != "https" ||
                resolved.host != "e2e.majorplay.net") {
                return reference
            }

            val path = resolved.rawPath ?: return reference
            val query = resolved.rawQuery
            return localBase + path +
                (if (query.isNullOrBlank()) "" else "?$query")
        }

        val uriPattern = Regex("""URI="([^"]+)"""")

        return manifest.lineSequence().map { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                !trimmed.startsWith("#") -> rewrite(trimmed)
                trimmed.startsWith("#EXT") &&
                    trimmed.contains("URI=") -> {
                    uriPattern.replace(line) { match ->
                        "URI=\"" + rewrite(match.groupValues[1]) + "\""
                    }
                }
                else -> line
            }
        }.joinToString("\n")
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = 30000
                val input = socket.getInputStream().bufferedReader()
                val request = input.readLine() ?: return
                val parts = request.split(" ")
                val method = parts.getOrNull(0) ?: return
                val path = parts.getOrNull(1) ?: return

                if (method != "GET" && method != "HEAD") return

                var range: String? = null
                while (true) {
                    val line = input.readLine() ?: return
                    if (line.isEmpty()) break
                    if (line.startsWith("Range:", ignoreCase = true)) {
                        range = line.substringAfter(":").trim()
                    }
                }

                val incoming = java.net.URI(path)
                val rawPath = incoming.rawPath ?: return
                if (!rawPath.startsWith("/") ||
                    rawPath.contains("..") ||
                    incoming.isAbsolute ||
                    incoming.rawFragment != null) return

                val query = incoming.rawQuery.orEmpty()
                val auth = authByVideo.entries
                    .filter { rawPath.startsWith(it.key) }
                    .maxByOrNull { it.key.length }
                    ?.value ?: ""

                if (auth.isBlank()) {
                    val response = (
                        "HTTP/1.1 403 Forbidden\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n\r\n"
                    )
                    socket.getOutputStream().write(
                        response.toByteArray(Charsets.UTF_8)
                    )
                    return
                }

                val existingKeys = query.split("&")
                    .filter { it.isNotBlank() }
                    .map { it.substringBefore("=") }
                    .toSet()

                val missingAuth = auth.split("&")
                    .filter { it.isNotBlank() }
                    .filter {
                        it.substringBefore("=") !in existingKeys
                    }

                val mergedQuery = (
                    listOf(query).filter { it.isNotBlank() } +
                    missingAuth
                ).joinToString("&")

                val targetUrl = "https://e2e.majorplay.net" +
                    rawPath +
                    (if (mergedQuery.isBlank()) "" else "?$mergedQuery")

                val connection = java.net.URL(targetUrl)
                    .openConnection() as java.net.HttpURLConnection

                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                range?.let {
                    connection.setRequestProperty("Range", it)
                }

                try {
                    val status = connection.responseCode
                    val contentType = connection.contentType
                        ?: "application/octet-stream"

                    val isManifest = status in 200..299 &&
                        (
                            contentType.contains("mpegurl", true) ||
                            rawPath.endsWith(".m3u8", true) ||
                            (rawPath.endsWith(".json", true) &&
                                contentType.contains("application/json", true))
                        )

                    val output = socket.getOutputStream()

                    if (isManifest) {
                        val original = connection.inputStream
                            .bufferedReader().use { it.readText() }

                        val rewritten = if (
                            original.trimStart().startsWith("#EXTM3U")
                        ) {
                            rewriteManifest(
                                original,
                                targetUrl,
                                server?.localPort ?: return
                            )
                        } else {
                            original
                        }

                        val body = rewritten.toByteArray(Charsets.UTF_8)
                        val header = (
                            "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        )

                        output.write(header.toByteArray(Charsets.UTF_8))
                        if (method == "GET") output.write(body)
                    } else {
                        val reason = when (status) {
                            200 -> "OK"
                            206 -> "Partial Content"
                            401 -> "Unauthorized"
                            403 -> "Forbidden"
                            404 -> "Not Found"
                            416 -> "Range Not Satisfiable"
                            else -> "Response"
                        }

                        val header = buildString {
                            append("HTTP/1.1 $status $reason\r\n")
                            append("Content-Type: $contentType\r\n")

                            connection.getHeaderField("Content-Length")
                                ?.let { append("Content-Length: $it\r\n") }

                            connection.getHeaderField("Content-Range")
                                ?.let { append("Content-Range: $it\r\n") }

                            append("Accept-Ranges: bytes\r\n")
                            append("Connection: close\r\n\r\n")
                        }

                        output.write(header.toByteArray(Charsets.UTF_8))

                        if (method == "GET") {
                            val stream = if (status < 400) {
                                connection.inputStream
                            } else {
                                connection.errorStream
                            }
                            stream?.use { it.copyTo(output) }
                        }
                    }

                    output.flush()
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
                // Koneksi gagal ditutup tanpa menghentikan server.
            }
        }
    }
}
