package io.github.nanomuse.preview

import org.junit.Assert.*
import org.junit.Test
import java.io.File

// nanoMuse: exercise the production preview URL and source-authorization policy.
class HtmlPreviewPolicyTest {
    private val policy = HtmlPreviewPolicy()
    private val files = File("/app/files")

    @Test fun unicodeAssetsAndLiteralPlusKeepTheirNames() {
        for (name in listOf("index.html", "你好 😀.html", "a+b.css", "20%off.js", "a#b?.html")) {
            assertEquals(listOf(name), policy.relativePath(policy.url(name)))
        }
        assertEquals(listOf("assets", "style.css"), policy.relativePath(policy.url("assets/style.css")))
    }

    @Test fun rejectsTraversalAndAmbiguousEncodings() {
        for (path in listOf("../private", "a/../../private", "%2e%2e/private", "%252e%252e/private",
            "%2fprivate", "a%5cb", "%00secret", "%ff", "a//b", ".env", "a/.secret", "a/..", "")) {
            assertNull(path, policy.relativePath(policy.origin + "/preview/" + path))
        }
    }

    @Test fun foreignOriginsSchemesPortsAndPathsAreNotAssets() {
        for (url in listOf("file:///app/files/private", "content://private/token", "http://" + policy.host + "/preview/index.html",
            "https://example.test/preview/index.html", "https://" + policy.host + ":443/preview/index.html",
            "https://user@" + policy.host + "/preview/index.html", policy.origin + "/private", "about:blank")) {
            assertNull(url, policy.relativePath(url))
        }
    }

    @Test fun eachPreviewGetsAFreshOrigin() {
        assertNotEquals(policy.origin, HtmlPreviewPolicy().origin)
        assertNull(HtmlPreviewPolicy().relativePath(policy.url("index.html")))
    }

    @Test fun onlyArtifactLocationsAuthorizeAParentDirectory() {
        for (path in listOf("minis-sessions/a/workspace/index.html", "minis-sessions/a/browser/site/index.html",
            "minis-sessions/a/attachments/index.htm", "sessions/a/attachments/index.xhtml",
            "minis-global/shared/site/index.html", "alpine-rootfs/root/site/index.html")) {
            val source = HtmlPreviewPolicy.source(files, File(files, path))
            assertNotNull(path, source)
            assertEquals(path.substringBeforeLast('/').split('/'), source!!.directory)
        }
        for (path in listOf("private.html", "shared_prefs/settings.html", "databases/token.html",
            "minis-global/memory/index.html", "minis-sessions/a/logs/index.html",
            "sessions/a/index.html", "minis-sessions/a/workspace/../../logs/index.html",
            "alpine-rootfs/root/.ssh/index.html", "minis-sessions/a/workspace/token.txt")) {
            assertNull(path, HtmlPreviewPolicy.source(files, File(files, path)))
        }
        assertNull(HtmlPreviewPolicy.source(files, File("/app/other/index.html")))
    }

    @Test fun filesystemAliasesCannotHideAMountInsidePrivateAppStorage() {
        val directory = java.nio.file.Files.createTempDirectory("f01-policy-").toFile()
        try {
            val realFiles = File(directory, "files").apply { mkdir() }
            val privateAssets = File(directory, "shared_prefs").apply { mkdir() }
            val aliasedFiles = File("/proc/self/root" + realFiles.absolutePath)
            assertEquals(realFiles.canonicalFile, aliasedFiles.canonicalFile)
            assertNull(HtmlPreviewPolicy.source(aliasedFiles, File(privateAssets, "index.html"), listOf(privateAssets)))
        } finally { directory.deleteRecursively() }
    }

    @Test fun explicitlyMountedDirectoriesNeverAuthorizeAppPrivateStorage() {
        val mounted = File("/storage/documents/site")
        assertNotNull(HtmlPreviewPolicy.source(files, File(mounted, "index.html"), listOf(mounted)))
        assertNull(HtmlPreviewPolicy.source(files, File("/app/shared_prefs/index.html"), listOf(File("/app"))))
        assertNull(HtmlPreviewPolicy.source(files, File("/storage/other/index.html"), listOf(mounted)))
    }
}
