package io.github.nanomuse.preview

import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID

// nanoMuse: each preview authorizes one artifact directory under a fresh, non-network origin.
internal class HtmlPreviewPolicy {
    val host = "preview-${UUID.randomUUID()}.invalid"
    val origin = "https://$host"

    fun url(path: String): String = URI("https", host, "/preview/$path", null).toASCIIString()

    fun relativePath(url: String): List<String>? = try {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawAuthority != host || !uri.rawPath.startsWith("/preview/")) null
        else decode(uri.rawPath.removePrefix("/preview/")).split('/').takeIf(::safeComponents)
    } catch (_: Exception) { null }

    companion object {
        data class Source(val storageRoot: File, val directory: List<String>)

        fun source(filesDir: File, file: File, mounts: List<File> = emptyList()): Source? {
            val base = filesDir.absoluteFile.toPath()
            val path = file.absoluteFile.toPath()
            if (path != path.normalize() || file.extension.lowercase() !in setOf("html", "htm", "xhtml")) return null
            if (path.startsWith(base)) {
                val parts = base.relativize(path).map { it.toString() }
                if (!safeComponents(parts)) return null
                val allowed = when (parts.firstOrNull()) {
                    "minis-sessions" -> parts.size >= 4 && parts[2] in setOf("workspace", "browser", "attachments")
                    "sessions" -> parts.size >= 4 && parts[2] == "attachments"
                    "minis-global" -> parts.size >= 3 && parts[1] == "shared"
                    "alpine-rootfs" -> parts.size >= 3 && parts[1] == "root"
                    else -> false
                }
                return if (allowed) Source(filesDir, parts.dropLast(1)) else null
            }
            // Explicit user mounts may authorize external files, never the app's private data tree.
            val privateRoot = filesDir.canonicalFile.toPath().parent ?: return null
            for (mount in mounts) {
                val root = mount.canonicalFile.toPath()
                if (root.startsWith(privateRoot) || privateRoot.startsWith(root) || !path.startsWith(root)) continue
                val parts = root.relativize(path).map { it.toString() }
                if (safeComponents(parts)) return Source(root.toFile(), parts.dropLast(1))
            }
            return null
        }

        private fun safeComponents(parts: List<String>): Boolean = parts.isNotEmpty() && parts.all { part ->
            part.isNotEmpty() && !part.startsWith('.') && part.none { it == '\\' || it.isISOControl() } &&
                !Regex("%[0-9a-fA-F]{2}").containsMatchIn(part)
        }

        private fun decode(path: String): String {
            val bytes = ByteArrayOutputStream()
            var i = 0
            while (i < path.length) {
                if (path[i] == '%') {
                    bytes.write(path.substring(i + 1, i + 3).toInt(16))
                    i += 3
                } else {
                    val point = path.codePointAt(i)
                    bytes.write(String(Character.toChars(point)).toByteArray(Charsets.UTF_8))
                    i += Character.charCount(point)
                }
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }
    }
}
