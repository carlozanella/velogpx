package ch.cld9.velogpx.share

import android.net.Uri
import androidx.core.content.FileProvider

/** Restricts shared files to the cache path declared in file_paths.xml and reports the GPX MIME type. */
class GpxFileProvider : FileProvider() {
    override fun getType(uri: Uri): String? =
        if (uri.lastPathSegment?.endsWith(".gpx", ignoreCase = true) == true) GPX_MIME_TYPE else super.getType(uri)
}
