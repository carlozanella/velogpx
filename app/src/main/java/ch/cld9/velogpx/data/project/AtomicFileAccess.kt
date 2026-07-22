package ch.cld9.velogpx.data.project

import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

interface AtomicFileAccess {
    fun prepareRead(file: File)
    fun write(file: File, block: (OutputStream) -> Unit)
}

class AndroidAtomicFileAccess : AtomicFileAccess {
    override fun prepareRead(file: File) {
        AtomicFile(file).openRead().use { }
    }

    override fun write(file: File, block: (OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            block(output)
            atomic.finishWrite(output)
        } catch (error: Throwable) {
            atomic.failWrite(output)
            throw error
        }
    }
}

class JvmAtomicFileAccess : AtomicFileAccess {
    override fun prepareRead(file: File) = Unit

    override fun write(file: File, block: (OutputStream) -> Unit) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.new")
        try {
            FileOutputStream(temporary).use { output ->
                block(output)
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}

internal fun AtomicFileAccess.copy(source: File, target: File) {
    write(target) { output -> source.inputStream().buffered().use { it.copyTo(output) } }
}
