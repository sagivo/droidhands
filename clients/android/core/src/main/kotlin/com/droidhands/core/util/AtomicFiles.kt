package com.droidhands.core.util

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-safe file helpers shared by the file-backed stores. */
internal object AtomicFiles {
    /**
     * Replaces [file] with [bytes] atomically: writes a temp file in the same directory, fsyncs
     * it, then renames it over [file]. The temp file is removed on failure.
     * @throws IOException if any step fails ([file] is then unchanged).
     */
    fun write(file: File, bytes: ByteArray) {
        val dir = file.absoluteFile.parentFile ?: throw IOException("No parent directory for $file")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create directory $dir")
        val tmp = File.createTempFile(".${file.name}.", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(bytes)
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }

    /** Moves an unreadable/corrupt [file] to `<name>.corrupt` (best effort, never throws). */
    fun quarantine(file: File) {
        try {
            val backup = File(file.absoluteFile.parentFile, "${file.name}.corrupt")
            Files.move(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            // Best effort: the next save replaces the corrupt file anyway.
        }
    }
}
