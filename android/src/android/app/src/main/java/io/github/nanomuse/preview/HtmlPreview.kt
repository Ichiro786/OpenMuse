package io.github.nanomuse.preview

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import com.openminis.app.R
import com.openminis.app.sandbox.PRootKernel
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream

// nanoMuse: one read-only, fail-closed boundary for chat, file-viewer and pinned HTML previews.
internal class HtmlPreview(context: Context, private val file: File) : Closeable {
    private val policy = HtmlPreviewPolicy()
    val url: String = policy.url(file.name)
    private val errorText = context.getString(R.string.filepreview_file_read_error)
    private var directory: ParcelFileDescriptor? = try {
        val mounts = PRootKernel.bindMounts.entries.filter { it.key.startsWith("/var/minis/mounts/") }
            .map { File(it.value) }
        HtmlPreviewPolicy.source(context.filesDir, file, mounts)?.let { source ->
            var parent = openDirectory(source.storageRoot.canonicalPath)
            try {
                for (name in source.directory) {
                    val next = openDirectory("/proc/self/fd/${parent.fd}/$name")
                    parent.close()
                    parent = next
                }
                parent
            } catch (error: Throwable) {
                parent.close()
                throw error
            }
        }
    } catch (_: Exception) { null }

    private val loader = WebViewAssetLoader.Builder().setDomain(policy.host)
        .addPathHandler("/preview/") { path ->
            try {
                val parts = policy.relativePath(policy.url(path)) ?: return@addPathHandler denied()
                val input = openAsset(parts)
                val extension = parts.last().substringAfterLast('.', "").lowercase()
                val mime = when (extension) {
                    "html", "htm" -> "text/html"
                    "js", "mjs" -> "application/javascript"
                    "css" -> "text/css"
                    else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                }
                WebResourceResponse(mime, "UTF-8", 200, "OK", headers, input)
            } catch (_: Exception) { denied() }
        }.build()

    val client = object : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
            intercept(request.url.toString(), request.method)

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
            request.method != "GET" || !allows(request.url.toString())
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun configure(view: WebView, javaScript: Boolean = true) {
        view.settings.apply {
            javaScriptEnabled = javaScript
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            blockNetworkLoads = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            databaseEnabled = false
            saveFormData = false
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        // The first-party cookie switch is global: do not change other browser tabs' policy.
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    fun allows(target: String): Boolean = policy.relativePath(target) != null

    fun fileUrl(target: String): String? = policy.relativePath(target)?.let { parts ->
        Uri.fromFile(File(file.parentFile, parts.joinToString("/"))).toString()
    }

    fun intercept(target: String, method: String = "GET"): WebResourceResponse {
        if (method != "GET" || !allows(target)) return denied()
        // A missing handler/file must never fall through to WebView's network stack.
        return loader.shouldInterceptRequest(Uri.parse(target)) ?: denied()
    }

    @Synchronized
    private fun openAsset(parts: List<String>): InputStream {
        // Duplicate under the same lock as close(), so a closed/reused fd cannot change the root.
        var parent = ParcelFileDescriptor.dup(directory?.fileDescriptor ?: throw IOException("Preview closed"))
        try {
            for (name in parts.dropLast(1)) {
                val next = openDirectory("/proc/self/fd/${parent.fd}/$name")
                parent.close()
                parent = next
            }
            val leaf = own(Os.open("/proc/self/fd/${parent.fd}/${parts.last()}",
                OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0))
            try {
                if (!OsConstants.S_ISREG(Os.fstat(leaf.fileDescriptor).st_mode)) throw IOException("Not a regular asset")
                return ParcelFileDescriptor.AutoCloseInputStream(leaf)
            } catch (error: Throwable) {
                leaf.close()
                throw error
            }
        } finally { parent.close() }
    }

    @Synchronized
    override fun close() {
        val previous = directory
        directory = null
        previous?.close()
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", headers,
        errorText.byteInputStream(Charsets.UTF_8))

    companion object {
        // Constrain fetches, workers and forms too. WebRTC is not an offline guarantee; see docs/android.md.
        private val headers = mapOf(
            "Content-Security-Policy" to "default-src 'self' data: blob:; script-src 'self' 'unsafe-inline' 'unsafe-eval'; " +
                "style-src 'self' 'unsafe-inline'; connect-src 'self'; frame-src 'none'; object-src 'none'; " +
                "worker-src 'none'; child-src 'none'; base-uri 'self'; form-action 'none'; frame-ancestors 'none'; " +
                "sandbox allow-scripts allow-same-origin allow-modals",
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Permissions-Policy" to "camera=(), microphone=(), geolocation=()",
        )

        private fun own(fd: FileDescriptor): ParcelFileDescriptor =
            try { ParcelFileDescriptor.dup(fd) } finally { Os.close(fd) }

        private fun openDirectory(path: String): ParcelFileDescriptor {
            val fd = own(Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0))
            try {
                if (!OsConstants.S_ISDIR(Os.fstat(fd.fileDescriptor).st_mode)) throw IOException("Not an asset directory")
                return fd
            } catch (error: Throwable) {
                fd.close()
                throw error
            }
        }
    }
}
