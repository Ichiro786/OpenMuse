package io.github.nanomuse.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.FileVisitResult
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Callable
import java.util.concurrent.Executors

// nanoMuse: test the production descriptor-based writer, including deterministic namespace swaps.
@RunWith(AndroidJUnit4::class)
class BrowserDownloadFilesInstrumentedTest {
    private lateinit var root: File
    private val workspace get() = File(root, "minis-sessions/test-session/workspace")

    @Before fun setUp() {
        root = Files.createTempDirectory(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath(), "download-files-").toFile()
    }

    @After fun tearDown() {
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

    private fun save(name: String, text: String = "payload"): File =
        BrowserDownloadFiles.write(root, "test-session", name) { _, output -> output.write(text.toByteArray()) }

    @Test fun ordinaryUnicodeDownloadsCreateWorkspaceAndPreserveBytes() {
        val file = save("报告😀.txt")
        assertEquals(workspace.canonicalFile, file.canonicalFile.parentFile)
        assertEquals("payload", file.readText())
    }

    @Test fun collisionsNeverReplaceFilesAndKeepExistingExtensionRules() {
        assertEquals("report.tar.gz", save("report.tar.gz", "first").name)
        assertEquals("report.tar-1.gz", save("report.tar.gz", "second").name)
        assertEquals("report.tar-2.gz", save("report.tar.gz", "third").name)
        assertEquals("first", File(workspace, "report.tar.gz").readText())
        assertEquals("second", File(workspace, "report.tar-1.gz").readText())
        assertEquals("plain", save("plain").name)
        assertEquals("plain-1", save("plain").name)
        assertEquals(".hidden", save(".hidden").name)
        assertEquals("-1.hidden", save(".hidden").name)
        File(workspace, "directory.txt").mkdir()
        assertEquals("directory-1.txt", save("directory.txt").name)
    }

    @Test fun existingAndDanglingSymlinksAreCollisionsNotWriteTargets() {
        workspace.mkdirs()
        val outside = File(root, "outside").apply { mkdir() }
        val canary = File(outside, "canary.txt").apply { writeText("untouched") }
        Files.createSymbolicLink(File(workspace, "report.txt").toPath(), canary.toPath())
        Files.createSymbolicLink(File(workspace, "missing.txt").toPath(), File(outside, "missing.txt").toPath())
        assertEquals("report-1.txt", save("report.txt").name)
        assertEquals("missing-1.txt", save("missing.txt").name)
        assertEquals("untouched", canary.readText())
        assertFalse(File(outside, "missing.txt").exists())
    }

    @Test fun rejectsSymlinkAtEveryUntrustedDirectoryComponent() {
        for (relative in listOf("minis-sessions", "minis-sessions/test-session",
            "minis-sessions/test-session/workspace", ".browser-downloads")) {
            val base = File(root, relative.replace('/', '_')).apply { mkdir() }
            val outside = File(base, "outside").apply { mkdir() }
            val link = File(base, relative)
            link.parentFile!!.mkdirs()
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            assertThrows(Exception::class.java) {
                BrowserDownloadFiles.write(base, "test-session", "report.txt") { _, output -> output.write(1) }
            }
            assertTrue("No directory or file may be created through a symlink", outside.listFiles()!!.isEmpty())
        }
    }

    @Test fun rejectsUnsafeSessionComponentsAndFilenamesBeforeCreatingDirectories() {
        for (name in listOf("../x", "a/b", "a\\b", File(root, "absolute").path, "", ".", "..", "a\u0000b")) {
            assertThrows(IOException::class.java) { save(name) }
        }
        assertThrows(IOException::class.java) {
            BrowserDownloadFiles.write(root, "../other-session", "report.txt") { _, output -> output.write(1) }
        }
        assertFalse(File(root, "minis-sessions").exists())
    }

    @Test fun leafInsertedDuringTransferIsNotOverwrittenOrRemoved() {
        val canary = File(root, "canary.txt").apply { writeText("untouched") }
        val saved = BrowserDownloadFiles.write(root, "test-session", "report.txt") { file, output ->
            Files.createSymbolicLink(file.toPath(), canary.toPath())
            output.write("payload".toByteArray())
        }
        assertEquals("report-1.txt", saved.name)
        assertEquals("payload", saved.readText())
        assertTrue(Files.isSymbolicLink(File(workspace, "report.txt").toPath()))
        assertEquals("untouched", canary.readText())
        assertTrue(File(root, ".browser-downloads").listFiles()!!.isEmpty())
    }

    @Test fun failedTransferCannotDeleteAnotherCompletedDownload() {
        assertThrows(IOException::class.java) {
            BrowserDownloadFiles.write(root, "test-session", "report.txt") { _, output ->
                output.write(1)
                save("report.txt", "keep")
                throw IOException("simulated transfer failure")
            }
        }
        assertEquals("keep", File(workspace, "report.txt").readText())
        assertTrue(File(root, ".browser-downloads").listFiles()!!.isEmpty())
    }

    @Test fun successfulTransferRejectsWorkspaceSwapBeforePublication() {
        val outside = File(root, "outside").apply { mkdir() }
        val canary = File(outside, "report.txt").apply { writeText("untouched") }
        assertThrows(Exception::class.java) {
            BrowserDownloadFiles.write(root, "test-session", "report.txt") { _, output ->
                Files.move(workspace.toPath(), File(workspace.parentFile, "original").toPath())
                Files.createSymbolicLink(workspace.toPath(), outside.toPath())
                output.write("payload".toByteArray())
            }
        }
        assertEquals("untouched", canary.readText())
        assertFalse(File(workspace.parentFile, "original/report.txt").exists())
        assertTrue(File(root, ".browser-downloads").listFiles()!!.isEmpty())
    }

    @Test fun rejectsNonDirectoryWorkspaceComponentsWithoutBlockingOnFifo() {
        val session = File(root, "minis-sessions/test-session").apply { mkdirs() }
        android.system.Os.mkfifo(File(session, "workspace").path, 0x180)
        assertThrows(IOException::class.java) { save("report.txt") }
    }

    @Test fun workspaceReplacementCannotRedirectWritesOrFailureCleanup() {
        val outside = File(root, "outside").apply { mkdir() }
        val canary = File(outside, "report.txt").apply { writeText("untouched") }
        assertThrows(IOException::class.java) {
            BrowserDownloadFiles.write(root, "test-session", "report.txt") { _, output ->
                Files.move(workspace.toPath(), File(workspace.parentFile, "original").toPath())
                Files.createSymbolicLink(workspace.toPath(), outside.toPath())
                output.write("payload".toByteArray())
                throw IOException("simulated transfer failure")
            }
        }
        assertEquals("untouched", canary.readText())
        assertFalse(File(workspace.parentFile, "original/report.txt").exists())
    }

    @Test fun failedTransferRemovesOnlyItsOwnReservation() {
        save("report.txt", "keep")
        assertThrows(IOException::class.java) {
            BrowserDownloadFiles.write(root, "test-session", "report.txt") { _, output ->
                output.write(1)
                throw IOException("simulated transfer failure")
            }
        }
        assertEquals("keep", File(workspace, "report.txt").readText())
        assertFalse(File(workspace, "report-1.txt").exists())
        assertTrue(File(root, ".browser-downloads").listFiles()!!.isEmpty())
    }

    @Test fun nativePublicationRejectsNonComponentByteArrays() {
        save("warmup.txt")
        val staging = File(root, ".browser-downloads")
        val source = File(staging, "source").apply { writeText("keep") }
        android.os.ParcelFileDescriptor.open(staging, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { from ->
            android.os.ParcelFileDescriptor.open(workspace, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { to ->
                for (name in listOf("", ".", "..", "../x", "/absolute", "a/b", "a\\b", "a\u0000b", "x".repeat(256))) {
                    assertEquals(android.system.OsConstants.EINVAL, BrowserDownloadNative.renameNoReplace(
                        from.fd, "source".toByteArray(), to.fd, name.toByteArray()))
                    assertEquals(android.system.OsConstants.EINVAL, BrowserDownloadNative.renameNoReplace(
                        from.fd, name.toByteArray(), to.fd, "unused".toByteArray()))
                }
            }
        }
        assertEquals("keep", source.readText())
        assertFalse(File(workspace, "unused").exists())
    }

    @Test fun transferStreamsCloseOnSuccessAndFailure() {
        for (fail in listOf(false, true)) {
            var stream: java.io.OutputStream? = null
            val transfer = {
                BrowserDownloadFiles.write(root, "test-session", "report.txt") { _, output ->
                    stream = output
                    output.write(1)
                    if (fail) throw IOException("simulated transfer failure")
                }
            }
            if (fail) assertThrows(IOException::class.java) { transfer() } else transfer()
            assertThrows(IOException::class.java) { checkNotNull(stream).write(1) }
            assertTrue(File(root, ".browser-downloads").listFiles()!!.isEmpty())
        }
    }

    @Test fun concurrentWritersReserveDistinctDestinationsAtomically() {
        val executor = Executors.newFixedThreadPool(6)
        try {
            val files = executor.invokeAll((1..12).map { i -> Callable { save("report.txt", i.toString()) } })
                .map { it.get() }
            assertEquals(12, files.map { it.name }.toSet().size)
            assertEquals((1..12).map { it.toString() }.toSet(), files.map { it.readText() }.toSet())
        } finally {
            executor.shutdownNow()
        }
    }
}
