package io.github.nanomuse.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// nanoMuse: exercise the filename policy used by both production browser download writers.
class BrowserDownloadFilesTest {
    @Test fun rejectsTraversalAndBothSeparators() {
        listOf("../x", "../../a/x", "a/../x", "a/x", "a\\x", "..\\x", "/tmp/x",
            "C:\\tmp\\x", "C:/tmp/x", "\\\\server\\share\\x").forEach {
            assertFalse(it, BrowserDownloadFiles.isValidName(it))
        }
    }

    @Test fun rejectsEmptyDotControlAndMalformedUnicodeNames() {
        listOf("", " ", "\t", ".", "..", "a\u0000b", "a\nb", "a\u007fb", "\uD800", "\uDC00").forEach {
            assertFalse(it, BrowserDownloadFiles.isValidName(it))
        }
    }

    @Test fun preservesUnicodeSpacesDotfilesAndExtensions() {
        listOf("report.txt", "my report.tar.gz", ".hidden", "报告.txt", "café.pdf",
            "照片😀.jpg", "e\u0301.txt", "a∕b.txt", "%2e%2e%2fx.txt").forEach {
            assertTrue(it, BrowserDownloadFiles.isValidName(it))
        }
    }

    @Test fun enforcesFilesystemByteLimitWithoutRejectingValidUnicode() {
        assertTrue(BrowserDownloadFiles.isValidName("a".repeat(255)))
        assertFalse(BrowserDownloadFiles.isValidName("a".repeat(256)))
        assertTrue(BrowserDownloadFiles.isValidName("é".repeat(127) + "a"))
        assertFalse(BrowserDownloadFiles.isValidName("é".repeat(128)))
    }
}
