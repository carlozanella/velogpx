package ch.cld9.velogpx.data

import android.content.Context
import ch.cld9.velogpx.io.GpxParser
import ch.cld9.velogpx.io.GpxWriter
import ch.cld9.velogpx.model.GpxDocument
import java.io.File

class ProjectStore(context: Context) {
    private val directory = File(context.filesDir, "projects").apply { mkdirs() }
    private val autosave = File(directory, "autosave.gpx")
    private val temporary = File(directory, "autosave.gpx.tmp")

    fun loadAutosave(): GpxDocument? {
        if (!autosave.isFile) return null
        return autosave.inputStream().use { GpxParser().parse(it, "Recovered project").document }
    }

    @Synchronized
    fun saveAutosave(document: GpxDocument) {
        temporary.outputStream().buffered().use { GpxWriter().write(document, it) }
        check(temporary.renameTo(autosave) || run {
            temporary.copyTo(autosave, overwrite = true)
            temporary.delete()
        }) { "Could not commit the project autosave" }
    }

    @Synchronized
    fun clear() {
        temporary.delete()
        autosave.delete()
    }
}
