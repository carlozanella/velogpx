package ch.cld9.velogpx.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.WorkerThread
import androidx.core.content.FileProvider
import ch.cld9.velogpx.R
import ch.cld9.velogpx.io.GpxWriter
import ch.cld9.velogpx.model.GpxVersion
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

const val GPX_MIME_TYPE = "application/gpx+xml"

enum class GpxShareDestination { GARMIN_CONNECT, SYSTEM_CHOOSER }

data class GpxShareLaunch(
    val intent: Intent,
    val destination: GpxShareDestination,
)

@ConsistentCopyVisibility
data class PreparedGpxFile internal constructor(
    val uri: Uri,
    val displayName: String,
    internal val cacheFile: File,
)

class PreparedGpxShare internal constructor(
    val files: List<PreparedGpxFile>,
    internal val sessionDirectory: File,
) {
    init {
        require(files.isNotEmpty()) { "A share must contain at least one GPX file" }
    }
}

internal fun interface ShareActivityResolver {
    fun canResolve(intent: Intent): Boolean
}

/**
 * Materializes GPX 1.1 files in private cache and creates permission-safe share intents.
 * Call [prepare] off the main thread, then pass [GpxShareLaunch.intent] to startActivity.
 */
class GpxShareService internal constructor(
    context: Context,
    private val writer: GpxWriter,
    private val activityResolver: ShareActivityResolver,
) {
    private val context = context.applicationContext
    private val rootDirectory = File(this.context.cacheDir, SHARE_CACHE_DIRECTORY)
    private val providerAuthority = "${this.context.packageName}.fileprovider"

    constructor(context: Context) : this(
        context = context,
        writer = GpxWriter(),
        activityResolver = AndroidShareActivityResolver(context.applicationContext),
    )

    @WorkerThread
    fun prepare(request: GpxShareRequest): PreparedGpxShare = prepare(listOf(request))

    @WorkerThread
    fun prepare(requests: List<GpxShareRequest>): PreparedGpxShare {
        require(requests.isNotEmpty()) { "At least one GPX selection is required" }
        pruneExpired()
        check(rootDirectory.mkdirs() || rootDirectory.isDirectory) { "Could not create the GPX share cache" }
        val session = File(rootDirectory, UUID.randomUUID().toString())
        check(session.mkdir()) { "Could not create a GPX share session" }

        return try {
            val usedNames = mutableSetOf<String>()
            val files = requests.map { request ->
                val materialized = request.materialize()
                val displayName = uniqueName(materialized.displayName, usedNames)
                val target = checkedChild(session, displayName)
                val temporary = checkedChild(session, ".$displayName-${UUID.randomUUID()}.tmp")
                FileOutputStream(temporary).use { output ->
                    writer.write(materialized.document, output, GpxVersion.V1_1)
                    output.fd.sync()
                }
                check(temporary.renameTo(target)) { "Could not commit $displayName to the share cache" }
                val uri = FileProvider.getUriForFile(context, providerAuthority, target)
                PreparedGpxFile(uri, displayName, target)
            }
            session.setLastModified(System.currentTimeMillis())
            PreparedGpxShare(files, session)
        } catch (error: Throwable) {
            session.deleteRecursively()
            throw error
        }
    }

    /**
     * Prefers Garmin Connect when it advertises support for the concrete action. If it does not,
     * this returns the normal Android chooser. A binary MIME fallback is tried only for Garmin.
     */
    fun createShareIntent(
        prepared: PreparedGpxShare,
        preferGarminConnect: Boolean = true,
        chooserTitle: CharSequence = context.getString(R.string.share_gpx_chooser_title),
    ): GpxShareLaunch {
        require(prepared.files.isNotEmpty()) { "The prepared share is empty" }
        val standard = buildSendIntent(prepared.files, GPX_MIME_TYPE)
        if (preferGarminConnect) {
            listOf(GPX_MIME_TYPE, COMPATIBILITY_MIME_TYPE).forEach { mimeType ->
                val direct = buildSendIntent(prepared.files, mimeType).setPackage(GARMIN_CONNECT_PACKAGE)
                if (activityResolver.canResolve(direct)) {
                    return GpxShareLaunch(direct, GpxShareDestination.GARMIN_CONNECT)
                }
            }
        }

        return GpxShareLaunch(
            intent = Intent.createChooser(standard, chooserTitle).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = standard.clipData
            },
            destination = GpxShareDestination.SYSTEM_CHOOSER,
        )
    }

    /**
     * Opens a single GPX as a document. Garmin Connect registers this ACTION_VIEW contract but,
     * on current Android releases, does not advertise itself for ACTION_SEND.
     */
    fun createOpenIntent(
        prepared: PreparedGpxShare,
        preferGarminConnect: Boolean = true,
        chooserTitle: CharSequence = context.getString(R.string.share_gpx_chooser_title),
    ): GpxShareLaunch {
        require(prepared.files.size == 1) { "Opening a GPX requires exactly one file" }
        val file = prepared.files.single()
        val standard = buildOpenIntent(file, GPX_MIME_TYPE)
        if (preferGarminConnect) {
            listOf(GPX_MIME_TYPE, COMPATIBILITY_MIME_TYPE).forEach { mimeType ->
                val direct = buildOpenIntent(file, mimeType).setPackage(GARMIN_CONNECT_PACKAGE)
                if (activityResolver.canResolve(direct)) {
                    return GpxShareLaunch(direct, GpxShareDestination.GARMIN_CONNECT)
                }
            }
        }
        return GpxShareLaunch(
            Intent.createChooser(standard, chooserTitle).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = standard.clipData
            },
            GpxShareDestination.SYSTEM_CHOOSER,
        )
    }

    /** Removes a prepared session after a cancelled share. Do not call while a receiver may still read it. */
    fun discard(prepared: PreparedGpxShare): Boolean {
        val session = prepared.sessionDirectory.canonicalFile
        if (session.parentFile != rootDirectory.canonicalFile) return false
        return session.deleteRecursively()
    }

    /** Removes old, complete sessions while retaining recent files for delayed share receivers. */
    fun pruneExpired(
        nowMillis: Long = System.currentTimeMillis(),
        maxAgeMillis: Long = DEFAULT_CACHE_RETENTION_MILLIS,
    ): Int {
        require(maxAgeMillis >= 0) { "Cache retention cannot be negative" }
        val root = rootDirectory.canonicalFile
        val cutoff = nowMillis - maxAgeMillis
        return rootDirectory.listFiles().orEmpty().count { candidate ->
            candidate.isDirectory && candidate.canonicalFile.parentFile == root && candidate.lastModified() < cutoff &&
                candidate.deleteRecursively()
        }
    }

    private fun buildSendIntent(files: List<PreparedGpxFile>, mimeType: String): Intent {
        val uris = files.map { it.uri }
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) {
                putExtra(Intent.EXTRA_STREAM, uris.single())
                putExtra(Intent.EXTRA_SUBJECT, files.single().displayName.removeSuffix(".gpx"))
            } else {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.clipData = ClipData.newUri(context.contentResolver, files.first().displayName, uris.first()).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        return intent
    }

    private fun buildOpenIntent(file: PreparedGpxFile, mimeType: String): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(file.uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, file.displayName, file.uri)
        }

    private fun uniqueName(requested: String, usedNames: MutableSet<String>): String {
        val base = requested.removeSuffix(".gpx")
        var candidate = requested
        var index = 2
        while (!usedNames.add(candidate.lowercase(Locale.ROOT))) {
            val suffix = " ($index)"
            candidate = sanitizeGpxFileName("${base.take(MAX_FILE_NAME_BASE_LENGTH - suffix.length)}$suffix")
            index += 1
        }
        return candidate
    }

    private fun checkedChild(parent: File, name: String): File = File(parent, name).canonicalFile.also {
        require(it.parentFile == parent.canonicalFile) { "Invalid share file name" }
    }

    private class AndroidShareActivityResolver(private val context: Context) : ShareActivityResolver {
        override fun canResolve(intent: Intent): Boolean {
            val packageManager = context.packageManager
            return if (Build.VERSION.SDK_INT >= 33) {
                packageManager.resolveActivity(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
                ) != null
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
            }
        }
    }

    companion object {
        const val GARMIN_CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"
        private const val SHARE_CACHE_DIRECTORY = "shared-gpx"
        private const val COMPATIBILITY_MIME_TYPE = "application/octet-stream"
        private const val MAX_FILE_NAME_BASE_LENGTH = 96
        private const val DEFAULT_CACHE_RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1_000L
    }
}
