package io.github.nanomuse.browser

import android.content.ContextWrapper
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.browser.BrowserUseManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// nanoMuse: real HTTP and JavaScript downloads use isolated app storage, never a person's chats.
@RunWith(AndroidJUnit4::class)
class BrowserDownloadIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var pool: BrowserTabPool
    private val workspace get() = File(root, "minis-sessions/test-session/workspace")

    @Before fun setUp() {
        root = Files.createTempDirectory(instrumentation.targetContext.cacheDir.toPath(), "downloads-").toFile()
        val context = object : ContextWrapper(instrumentation.targetContext) {
            override fun getFilesDir(): File = root
        }
        pool = BrowserTabPool(context).also { it.setSession("test-session") }
    }

    @After fun tearDown() {
        // No production lifecycle changes for F02: cancel the pool's existing private scopes here.
        runBlocking {
            for (name in listOf("downloadScope", "evictionScope")) {
                val field = BrowserTabPool::class.java.getDeclaredField(name).apply { isAccessible = true }
                (field.get(pool) as CoroutineScope).coroutineContext[Job]?.cancelAndJoin()
            }
        }
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }

    @Test fun blobRejectsTraversalAndInvalidNamesBeforeScheduling() {
        for (name in listOf("../escape.txt", "../../../escape.txt", "a/b.txt", "a\\b.txt",
            File(root, "absolute.txt").path, "", " ", ".", "..", "bad\u0000.txt", "bad\n.txt")) {
            pool.saveBlobDownload("payload".toByteArray(), name, null)
            assertTrue("Rejected name registered a download: $name", pool.downloads.value.isEmpty())
        }
        assertFalse(File(root, "escape.txt").exists())
    }

    @Test fun blobPreservesUnicodeMimeExtensionsAndCollisions() {
        pool.saveBlobDownload("first".toByteArray(), "报告.bin", "text/plain")
        awaitTerminal(1)
        pool.saveBlobDownload("second".toByteArray(), "报告.bin", "text/plain")
        awaitTerminal(2)
        assertEquals("first", File(workspace, "报告.txt").readText())
        assertEquals("second", File(workspace, "报告-1.txt").readText())
    }

    @Test fun httpDownloadPreservesOrdinaryFiles() {
        HttpFixture().use { server ->
            pool.startUrlDownload(server.url, null, "attachment; filename=\"report.txt\"", "text/plain", 7)
            awaitTerminal(1)
        }
        assertEquals("payload", File(workspace, "report.txt").readText())
        assertEquals(BrowserTabPool.DownloadState.COMPLETED, pool.downloads.value.single().state)
    }

    @Test fun httpHeaderCannotEscapeWorkspace() {
        HttpFixture().use { server ->
            pool.startUrlDownload(server.url, null, "attachment; filename=\"../../../escape.txt\"", "text/plain", 7)
            awaitTerminal(1)
        }
        assertFalse(File(root, "escape.txt").exists())
        val entry = pool.downloads.value.single()
        assertEquals(BrowserTabPool.DownloadState.COMPLETED, entry.state)
        assertEquals(workspace.canonicalFile, checkNotNull(entry.destination).canonicalFile.parentFile)
    }

    @Test fun workspaceSwapWhileHttpWaitsCannotOverwriteOutside() {
        val outside = File(root, "outside").apply { mkdir() }
        val canary = File(outside, "report.txt").apply { writeText("untouched") }
        HttpFixture(blocked = true).use { server ->
            pool.startUrlDownload(server.url, null, "attachment; filename=\"report.txt\"", "text/plain", 7)
            assertTrue(server.requestSeen.await(10, TimeUnit.SECONDS))
            Files.move(workspace.toPath(), File(workspace.parentFile, "original-workspace").toPath())
            Files.createSymbolicLink(workspace.toPath(), outside.toPath())
            server.releaseResponse.countDown()
            awaitTerminal(1)
        }
        assertEquals("untouched", canary.readText())
        assertEquals(BrowserTabPool.DownloadState.FAILED, pool.downloads.value.single().state)
    }

    @Test fun cancellationPreservesExistingCollisionAndRemovesPartial() {
        File(workspace, "report.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        HttpFixture(blocked = true).use { server ->
            pool.startUrlDownload(server.url, null, "attachment; filename=\"report.txt\"", "text/plain", 7)
            assertTrue(server.requestSeen.await(10, TimeUnit.SECONDS))
            pool.cancelDownload(pool.downloads.value.single().id)
            server.releaseResponse.countDown()
            awaitTerminal(1)
        }
        assertEquals("keep", File(workspace, "report.txt").readText())
        assertFalse(File(workspace, "report-1.txt").exists())
        assertEquals(BrowserTabPool.DownloadState.FAILED, pool.downloads.value.single().state)
        assertTrue(File(root, ".browser-downloads").listFiles()!!.isEmpty())
    }

    @Test fun httpFailureRemovesStagingAndKeepsExistingFiles() {
        File(workspace, "report.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
        HttpFixture(status = "404 Not Found").use { server ->
            pool.startUrlDownload(server.url, null, "attachment; filename=\"report.txt\"", "text/plain", 7)
            awaitTerminal(1)
        }
        assertEquals(BrowserTabPool.DownloadState.FAILED, pool.downloads.value.single().state)
        assertEquals("keep", File(workspace, "report.txt").readText())
        assertFalse(File(workspace, "report-1.txt").exists())
        assertTrue(File(root, ".browser-downloads").listFiles()!!.isEmpty())
    }

    @Test fun concurrentBlobDownloadsKeepEveryRegistryEntryAndDestination() {
        repeat(24) { i -> pool.saveBlobDownload(i.toString().toByteArray(), "report.txt", "text/plain") }
        awaitTerminal(24)
        val entries = pool.downloads.value
        assertTrue(entries.all { it.state == BrowserTabPool.DownloadState.COMPLETED })
        assertEquals(24, entries.map { it.id }.toSet().size)
        assertEquals(24, entries.map { it.filename }.toSet().size)
        assertEquals((0..23).map { it.toString() }.toSet(),
            entries.map { checkNotNull(it.destination).readText() }.toSet())
        assertTrue(File(root, ".browser-downloads").listFiles()!!.isEmpty())
    }

    @Test fun javascriptBridgeRejectsTraversal() {
        runBridge("data:text/plain;base64,b2s=", "../../../escape.txt")
        assertTrue(pool.downloads.value.isEmpty())
        assertFalse(File(root, "escape.txt").exists())
    }

    @Test fun javascriptBridgeSavesBase64AndPercentEncodedData() {
        runBridge("data:text/plain;base64,b2s=", "你好.txt")
        awaitTerminal(1)
        runBridge("data:text/plain,hello%20there", "second.txt")
        awaitTerminal(2)
        assertEquals("ok", File(workspace, "你好.txt").readText())
        assertEquals("hello there", File(workspace, "second.txt").readText())
    }

    private fun runBridge(dataUrl: String, name: String) {
        val delivered = CountDownLatch(1)
        var view: WebView? = null
        try {
            instrumentation.runOnMainSync {
                view = WebView(instrumentation.targetContext).also { webView ->
                    BrowserUseManager(webView).onBlobDownloadData = { bytes, filename, mime ->
                        try { pool.saveBlobDownload(bytes, filename, mime) }
                        finally { delivered.countDown() }
                    }
                    webView.loadDataWithBaseURL("https://example.test/",
                        "<script>__minis__.saveBlobDownload(${JSONObject.quote(dataUrl)},${JSONObject.quote(name)});</script>",
                        "text/html", "UTF-8", null)
                }
            }
            assertTrue("Bridge was not called", delivered.await(10, TimeUnit.SECONDS))
        } finally {
            instrumentation.runOnMainSync { view?.destroy() }
        }
    }

    private fun awaitTerminal(count: Int) = runBlocking {
        withTimeout(10_000) {
            pool.downloads.first { it.size == count && it.none { entry ->
                entry.state == BrowserTabPool.DownloadState.DOWNLOADING
            } }
        }
    }

    private class HttpFixture(blocked: Boolean = false, status: String = "200 OK") : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}/download"
        val requestSeen = CountDownLatch(1)
        val releaseResponse = CountDownLatch(if (blocked) 1 else 0)
        private val failure = java.util.concurrent.atomic.AtomicReference<Exception?>()
        private val worker = Thread {
            try {
                server.accept().use { client ->
                    client.soTimeout = 10_000
                    val reader = client.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) { }
                    requestSeen.countDown()
                    check(releaseResponse.await(10, TimeUnit.SECONDS))
                    client.getOutputStream().write(
                        "HTTP/1.1 $status\r\nContent-Length: 7\r\nConnection: close\r\n\r\npayload".toByteArray())
                }
            } catch (error: Exception) {
                // A cancelled HTTP client can disconnect while the fixture writes its response.
                if (!server.isClosed && error !is java.net.SocketException) failure.set(error)
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            releaseResponse.countDown()
            server.close()
            worker.join(10_000)
            check(!worker.isAlive) { "HTTP fixture did not stop" }
            failure.get()?.let { throw AssertionError("HTTP fixture failed", it) }
        }
    }
}
