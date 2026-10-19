package io.github.nanomuse.browser

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

// nanoMuse: pin directories, stage privately, then publish downloads atomically without replacement.
internal object BrowserDownloadFiles {
    fun isValidName(name: String): Boolean =
        name.length <= 255 && name.isNotBlank() && name != "." && name != ".." &&
            name.none { it == '/' || it == '\\' || it.isISOControl() } &&
            Charsets.UTF_8.newEncoder().canEncode(name) &&
            name.toByteArray(Charsets.UTF_8).size <= 255

    // The callback receives a provisional destination; the returned File has the final collision suffix.
    fun write(filesDir: File, sessionId: String, name: String, transfer: (File, OutputStream) -> Unit): File {
        if (!isValidName(name) || !isValidName(sessionId)) throw IOException("Invalid download filename or session")
        // Only Android's trusted app-storage root may be canonicalized; untrusted children are never followed.
        val root = filesDir.canonicalFile
        val workspacePath = File(File(File(root, "minis-sessions"), sessionId), "workspace")
        return openDirectory(root.path).use { app ->
            inDirectory(app, "minis-sessions") { sessions ->
                inDirectory(sessions, sessionId) { session ->
                    inDirectory(session, "workspace") { workspace ->
                        // Staging is outside the tool workspace: failure cleanup never unlinks a public filename.
                        inDirectory(app, ".browser-downloads") { staging ->
                            val temporaryName = UUID.randomUUID().toString()
                            val temporary = "${descriptorPath(staging)}/$temporaryName"
                            val raw = Os.open(temporary, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                                OsConstants.O_EXCL or OsConstants.O_NOFOLLOW, 0x180) // 0600
                            var failure: Throwable? = null
                            var published = false
                            try {
                                // Duplication can fail under FD exhaustion; the stage still needs cleanup.
                                ParcelFileDescriptor.AutoCloseOutputStream(own(raw)).use { output ->
                                    transfer(File(workspacePath, name), output)
                                }
                                verifyWorkspace(app, sessionId, workspace)
                                val destination = publish(staging, temporaryName, workspace, workspacePath, name)
                                published = true
                                destination
                            } catch (error: Throwable) {
                                failure = error
                                throw error
                            } finally {
                                if (!published) {
                                    try { Os.remove(temporary) } catch (cleanup: Exception) {
                                        if (failure == null) throw cleanup
                                        failure.addSuppressed(cleanup)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun publish(
        staging: ParcelFileDescriptor,
        temporaryName: String,
        directory: ParcelFileDescriptor,
        workspace: File,
        name: String,
    ): File {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "")
        var attempt = 0
        while (true) {
            val candidate = if (attempt == 0) name else
                if (extension.isEmpty()) "$base-$attempt" else "$base-$attempt.$extension"
            if (!isValidName(candidate)) throw IOException("Download filename is too long")
            try {
                // Public Android APIs lack a rename that atomically refuses existing destinations.
                val error = BrowserDownloadNative.renameNoReplace(
                    staging.fd, temporaryName.toByteArray(Charsets.UTF_8),
                    directory.fd, candidate.toByteArray(Charsets.UTF_8),
                )
                if (error != 0) throw ErrnoException("renameat2", error)
                return File(workspace, candidate)
            } catch (error: ErrnoException) {
                if (error.errno != OsConstants.EEXIST) throw error
                attempt++
            }
        }
    }

    private fun verifyWorkspace(app: ParcelFileDescriptor, sessionId: String, expected: ParcelFileDescriptor) {
        openDirectory("${descriptorPath(app)}/minis-sessions").use { sessions ->
            openDirectory("${descriptorPath(sessions)}/$sessionId").use { session ->
                openDirectory("${descriptorPath(session)}/workspace").use { current ->
                    if (!sameFile(Os.fstat(expected.fileDescriptor), Os.fstat(current.fileDescriptor))) {
                        throw IOException("Download workspace changed during transfer")
                    }
                }
            }
        }
    }

    private fun sameFile(first: StructStat, second: StructStat): Boolean =
        first.st_dev == second.st_dev && first.st_ino == second.st_ino

    private fun descriptorPath(directory: ParcelFileDescriptor): String = "/proc/self/fd/${directory.fd}"

    private fun openDirectory(path: String): ParcelFileDescriptor {
        // O_DIRECTORY is not public Android SDK; fstat checks the opened inode, with no check/open race.
        val directory = own(Os.open(path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
            OsConstants.O_NONBLOCK, 0))
        try {
            if (!OsConstants.S_ISDIR(Os.fstat(directory.fileDescriptor).st_mode)) throw IOException("Not a directory")
            return directory
        } catch (failure: Throwable) {
            directory.close()
            throw failure
        }
    }

    private fun own(fd: FileDescriptor): ParcelFileDescriptor =
        try { ParcelFileDescriptor.dup(fd) } finally { Os.close(fd) }

    private inline fun <T> inDirectory(
        parent: ParcelFileDescriptor,
        name: String,
        block: (ParcelFileDescriptor) -> T,
    ): T {
        val path = "${descriptorPath(parent)}/$name"
        // The descriptor pins mkdir and open to the same parent, even if an ancestor is renamed.
        try { Os.mkdir(path, 0x1C0) } catch (error: ErrnoException) { // 0700
            if (error.errno != OsConstants.EEXIST) throw error
        }
        return openDirectory(path).use(block)
    }
}

// nanoMuse: load the existing JNI library only when publishing, leaving filename-policy JVM tests portable.
internal object BrowserDownloadNative {
    init { System.loadLibrary("pty_bridge") }

    external fun renameNoReplace(
        sourceDir: Int,
        sourceName: ByteArray,
        destinationDir: Int,
        destinationName: ByteArray,
    ): Int
}
