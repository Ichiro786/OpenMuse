package io.github.nanomuse.preview

import android.content.ContextWrapper
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.ui.preview.WebViewHolder
import org.json.JSONTokener
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

// nanoMuse: real production holders, asset responses and WebViews, isolated from a person's chats.
@RunWith(AndroidJUnit4::class)
class HtmlPreviewIsolationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var context: ContextWrapper
    private val previews = mutableListOf<HtmlPreview>()
    private val views = mutableListOf<WebView>()
    private val workspace get() = File(root, "minis-sessions/test/workspace")
    private val entry get() = File(workspace, "index.html")

    @Before fun setUp() {
        root = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "f01-").toFile()
        context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir() = root
        }
        workspace.mkdirs()
        entry.writeText("<h1>Preview</h1>")
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { views.forEach { it.stopLoading(); it.destroy() } }
        previews.forEach { it.close() }
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    // On the original holder, all four assertions fail: file URLs had unrestricted private-file access.
    @Test fun localHolderNeverGrantsFileOrContentAccess() {
        instrumentation.runOnMainSync {
            val holder = WebViewHolder(context, Uri.fromFile(entry).toString())
            try {
                with(holder.webView.settings) {
                    assertFalse(allowFileAccess)
                    assertFalse(allowContentAccess)
                    assertFalse(allowFileAccessFromFileURLs)
                    assertFalse(allowUniversalAccessFromFileURLs)
                    assertTrue(blockNetworkLoads)
                }
            } finally { holder.destroy() }
        }
    }

    @Test fun ordinaryHtmlRendersRelativeCssImagesAndJavaScript() {
        File(workspace, "style.css").writeText("#status { color: rgb(120, 120, 120); }")
        File(workspace, "app.js").writeText("document.getElementById('status').textContent = 'ready 😀';")
        File(workspace, "pixel.png").outputStream().use {
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            try { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } finally { bitmap.recycle() }
        }
        entry.writeText("<link rel='stylesheet' href='style.css'><p id='status'>waiting</p>" +
            "<img id='pixel' src='pixel.png'><script src='app.js'></script>")
        val view = render(preview())
        assertEquals("ready 😀", evaluate(view, "document.getElementById('status').textContent"))
        assertEquals("rgb(120, 120, 120)", evaluate(view, "getComputedStyle(document.getElementById('status')).color"))
        assertEquals(1, evaluate(view, "document.getElementById('pixel').naturalWidth"))
    }

    @Test fun relativeFetchesRemainInsideThePreviewDirectory() {
        File(workspace, "data.json").writeText("{\"value\":42}")
        entry.writeText("<script>window.result='pending'; fetch('data.json').then(r=>r.json())" +
            ".then(d=>window.result=d.value).catch(()=>window.result='blocked');</script>")
        val assets = preview()
        val view = render(assets)
        assertEquals(42, awaitValue(view, "window.result", "pending"))
        assertEquals("{\"value\":42}", read(assets.intercept(assets.url.replace("index.html", "data.json"))))
    }

    @Test fun privateFileCanaryCannotBeReadByPreviewJavaScript() {
        val canary = File(root, "private-token.txt").apply { writeText("PRIVATE_CANARY") }
        entry.writeText("<script>window.result='pending'; fetch(" + org.json.JSONObject.quote(Uri.fromFile(canary).toString()) +
            ").then(r=>r.text()).then(t=>window.result=t).catch(()=>window.result='blocked');</script>")
        val view = render(preview())
        assertEquals("blocked", awaitValue(view, "window.result", "pending"))
    }

    @Test fun traversalEncodedTraversalAndPrivateSchemesReturnNoCanary() {
        val canary = File(root, "private-token.txt").apply { writeText("PRIVATE_CANARY") }
        val assets = preview()
        val base = assets.url.substringBeforeLast('/') + "/"
        for (url in listOf(base + "../private-token.txt", base + "%2e%2e/private-token.txt",
            base + "%252e%252e/private-token.txt", base + "a%5c..%5cprivate-token.txt",
            Uri.fromFile(canary).toString(), "content://private/token", "https://appassets.androidplatform.net/private-token.txt")) {
            val response = assets.intercept(url)
            assertEquals(url, 403, response.statusCode)
            assertFalse(read(response).contains("PRIVATE_CANARY"))
        }
    }

    @Test fun unapprovedSourceDirectoryCannotBeMounted() {
        val file = File(root, "shared_prefs/index.html").apply { parentFile!!.mkdirs(); writeText("PRIVATE_CANARY") }
        val assets = HtmlPreview(context, file).also { previews.add(it) }
        val response = assets.intercept(assets.url)
        assertEquals(403, response.statusCode)
        assertFalse(read(response).contains("PRIVATE_CANARY"))
    }

    @Test fun symlinkLeavesAndDirectoriesCannotEscape() {
        val outside = File(root, "outside").apply { mkdir() }
        val canary = File(outside, "secret.css").apply { writeText("PRIVATE_CANARY") }
        Files.createSymbolicLink(File(workspace, "leaf.css").toPath(), canary.toPath())
        Files.createSymbolicLink(File(workspace, "linked").toPath(), outside.toPath())
        val assets = preview()
        for (path in listOf("leaf.css", "linked/secret.css")) {
            val response = assets.intercept(assets.url.replace("index.html", path))
            assertEquals(403, response.statusCode)
            assertFalse(read(response).contains("PRIVATE_CANARY"))
        }
    }

    @Test fun symlinkSourceDirectoryIsNotAuthorized() {
        val outside = File(root, "outside").apply { mkdir() }
        File(outside, "index.html").writeText("PRIVATE_CANARY")
        Files.move(workspace.toPath(), File(workspace.parentFile, "original").toPath())
        Files.createSymbolicLink(workspace.toPath(), outside.toPath())
        val assets = preview()
        assertEquals(403, assets.intercept(assets.url).also { it.data.close() }.statusCode)
    }

    @Test fun replacingTheRootCannotRedirectAnOpenPreview() {
        val assets = preview()
        val outside = File(root, "outside").apply { mkdir() }
        File(outside, "index.html").writeText("PRIVATE_CANARY")
        Files.move(workspace.toPath(), File(workspace.parentFile, "original").toPath())
        Files.createSymbolicLink(workspace.toPath(), outside.toPath())
        assertEquals("<h1>Preview</h1>", read(assets.intercept(assets.url)))
    }

    @Test fun externalNavigationAndResourceRequestsAreBlocked() {
        val assets = preview()
        val view = render(assets)
        for (url in listOf("https://example.test/", "http://127.0.0.1/", "intent://private", "file:///etc/passwd")) {
            instrumentation.runOnMainSync { assertTrue(assets.client.shouldOverrideUrlLoading(view, Request(url))) }
            assertEquals(403, assets.intercept(url).also { it.data.close() }.statusCode)
        }
        instrumentation.runOnMainSync { assertFalse(assets.client.shouldOverrideUrlLoading(view, Request(assets.url + "#section"))) }
        assertEquals(403, assets.intercept(assets.url, "POST").also { it.data.close() }.statusCode)
    }

    @Test fun previewJavaScriptCannotContactLoopback() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 500
            entry.writeText("<script>window.result='pending'; fetch('http://127.0.0.1:${server.localPort}/exfiltrate')" +
                ".then(()=>window.result='contacted').catch(()=>window.result='blocked');</script>")
            val view = render(preview())
            assertEquals("blocked", awaitValue(view, "window.result", "pending"))
            assertThrows(SocketTimeoutException::class.java) { server.accept().close() }
        }
    }

    @Test fun noApplicationBridgeOrInheritedBrowserCookieIsExposed() {
        val cookie = "f01_" + java.util.UUID.randomUUID().toString().replace("-", "")
        val cookies = CookieManager.getInstance()
        val accepted = cookies.acceptCookie()
        try {
            cookies.setAcceptCookie(true)
            val stored = CountDownLatch(1)
            instrumentation.runOnMainSync {
                cookies.setCookie("https://appassets.androidplatform.net/", "$cookie=PRIVATE_CANARY") { stored.countDown() }
            }
            assertTrue(stored.await(5, TimeUnit.SECONDS))
            assertTrue(cookies.getCookie("https://appassets.androidplatform.net/").orEmpty().contains("$cookie=PRIVATE_CANARY"))
            val view = render(preview())
            assertFalse(evaluate(view, "document.cookie").toString().contains("PRIVATE_CANARY"))
            assertEquals("undefined:undefined:undefined", evaluate(view,
                "typeof __minis__ + ':' + typeof Android + ':' + typeof nanomuse"))
        } finally {
            cookies.setCookie("https://appassets.androidplatform.net/", "$cookie=; Max-Age=0")
            cookies.setAcceptCookie(accepted)
        }
    }

    @Test fun previewConfigurationDoesNotChangeGlobalCookies() {
        val cookies = CookieManager.getInstance()
        val accepted = cookies.acceptCookie()
        try {
            cookies.setAcceptCookie(false)
            render(preview())
            assertFalse(cookies.acceptCookie())
        } finally { cookies.setAcceptCookie(accepted) }
    }

    @Test fun closingRevokesResourcesAndHolderTeardownIsIdempotent() {
        val assets = preview()
        val response = assets.intercept(assets.url)
        response.data.close()
        assertThrows(IOException::class.java) { response.data.read() }
        assets.close()
        assets.close()
        assertEquals(403, assets.intercept(assets.url).also { it.data.close() }.statusCode)
        instrumentation.runOnMainSync {
            val holder = WebViewHolder(context, Uri.fromFile(entry).toString())
            holder.startIfNeeded()
            holder.destroy()
            holder.destroy()
            holder.startIfNeeded()
        }
    }

    private fun preview() = HtmlPreview(context, entry).also { previews.add(it) }
    private fun read(response: WebResourceResponse) = response.data.bufferedReader().use { it.readText() }

    private fun render(assets: HtmlPreview): WebView {
        val finished = CountDownLatch(1)
        lateinit var view: WebView
        instrumentation.runOnMainSync {
            view = WebView(context).also { views.add(it) }
            assets.configure(view)
            view.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                    assets.client.shouldInterceptRequest(view, request)
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                    assets.client.shouldOverrideUrlLoading(view, request)
                override fun onPageFinished(view: WebView, url: String) { finished.countDown() }
            }
            view.loadUrl(assets.url)
        }
        assertTrue("Preview did not finish loading", finished.await(10, TimeUnit.SECONDS))
        return view
    }

    private fun evaluate(view: WebView, script: String): Any {
        val result = AtomicReference<String>()
        val completed = CountDownLatch(1)
        instrumentation.runOnMainSync { view.evaluateJavascript(script) { result.set(it); completed.countDown() } }
        assertTrue(completed.await(5, TimeUnit.SECONDS))
        return JSONTokener(result.get()).nextValue()
    }

    private fun awaitValue(view: WebView, script: String, initial: String): Any {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var value = evaluate(view, script)
        while (value == initial && System.nanoTime() < deadline) {
            Thread.sleep(20)
            value = evaluate(view, script)
        }
        return value
    }

    private class Request(private val target: String) : WebResourceRequest {
        override fun getUrl() = Uri.parse(target)
        override fun isForMainFrame() = true
        override fun isRedirect() = false
        override fun hasGesture() = true
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
