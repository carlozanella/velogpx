package ch.cld9.velogpx.data.project

import ch.cld9.velogpx.io.GpxParser
import ch.cld9.velogpx.io.GpxWriter
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.TrackStyle
import org.json.JSONArray
import org.json.JSONObject
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ProjectArchiveCodec(
    private val parser: GpxParser = GpxParser(),
    private val writer: GpxWriter = GpxWriter(),
) {
    fun write(project: ProjectState, output: OutputStream) {
        val zip = ZipOutputStream(NonClosingOutputStream(output)).apply { setLevel(1) }
        try {
            zip.putNextEntry(archiveEntry(MIMETYPE_ENTRY))
            zip.write(PROJECT_MIME_TYPE.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            val digest = MessageDigest.getInstance("SHA-256")
            val counter = CountingOutputStream(DigestOutputStream(zip, digest))
            zip.putNextEntry(archiveEntry(GPX_ENTRY))
            writer.write(project.document, counter, project.document.version)
            counter.flush()
            zip.closeEntry()

            val manifest = ProjectJson.encodeManifest(
                project = project,
                gpxSha256 = digest.digest().toHex(),
                gpxByteLength = counter.count,
            ).toString().toByteArray(Charsets.UTF_8)
            check(manifest.size <= MAX_MANIFEST_BYTES) { "Project manifest is unexpectedly large" }
            zip.putNextEntry(archiveEntry(MANIFEST_ENTRY))
            zip.write(manifest)
            zip.closeEntry()
            zip.finish()
            zip.flush()
        } finally {
            runCatching { zip.finish() }
        }
    }

    fun read(file: File): ProjectState {
        try {
            ZipFile(file).use { zip ->
                validateEntries(zip)
                val manifest = readManifest(zip)
                val descriptor = manifest.getJSONObject("document")
                val expectedLength = descriptor.getLong("byteLength")
                require(expectedLength in 0..MAX_GPX_BYTES) { "GPX entry exceeds the project size limit" }
                val entry = zip.getEntry(GPX_ENTRY) ?: throw ProjectFormatException("Project has no document.gpx entry")
                if (entry.size >= 0 && entry.size > MAX_GPX_BYTES) throw ProjectFormatException("GPX entry exceeds the project size limit")

                val digest = MessageDigest.getInstance("SHA-256")
                val counted = CountingInputStream(DigestInputStream(zip.getInputStream(entry), digest), MAX_GPX_BYTES)
                val parsed = counted.use { parser.parse(it, manifest.getString("title")) }
                val rawDocument = parsed.document ?: throw ProjectFormatException(
                    parsed.issues.firstOrNull()?.message ?: "The archived GPX document is invalid",
                )
                val actualDigest = digest.digest().toHex()
                if (!actualDigest.equals(descriptor.getString("sha256"), ignoreCase = true)) {
                    throw ProjectFormatException("The project GPX checksum does not match its manifest")
                }
                if (counted.count != expectedLength) throw ProjectFormatException("The project GPX length does not match its manifest")
                return ProjectJson.decodeState(manifest, restoreIds(rawDocument, descriptor)).sanitizeEditorReferences()
            }
        } catch (error: ProjectFormatException) {
            throw error
        } catch (error: Exception) {
            throw ProjectFormatException("Could not read project archive ${file.name}: ${error.message}", error)
        }
    }

    fun readSummary(file: File, lastOpenedAt: Instant? = null): ProjectSummary {
        try {
            ZipFile(file).use { zip ->
                validateEntries(zip)
                val manifest = readManifest(zip)
                return ProjectJson.decodeSummary(manifest, lastOpenedAt)
            }
        } catch (error: ProjectFormatException) {
            throw error
        } catch (error: Exception) {
            throw ProjectFormatException("Could not read project summary ${file.name}: ${error.message}", error)
        }
    }

    private fun restoreIds(document: GpxDocument, descriptor: JSONObject): GpxDocument {
        val trackIds = descriptor.stringList("trackIdsInGpxOrder")
        val routeIds = descriptor.stringList("routeIdsInGpxOrder")
        val waypointIds = descriptor.stringList("waypointIdsInGpxOrder")
        val segmentIds = descriptor.optJSONArray("segmentIdsByTrack") ?: JSONArray()
        if (trackIds.size != document.tracks.size || routeIds.size != document.routes.size ||
            waypointIds.size != document.waypoints.size || segmentIds.length() != document.tracks.size
        ) throw ProjectFormatException("Project identity table does not match its GPX document")

        val tracks = document.tracks.mapIndexed { trackIndex, track ->
            val ids = segmentIds.getJSONArray(trackIndex).strings()
            if (ids.size != track.segments.size) throw ProjectFormatException("Project segment identity table is inconsistent")
            track.copy(
                id = trackIds[trackIndex],
                segments = track.segments.mapIndexed { segmentIndex, segment -> segment.copy(id = ids[segmentIndex]) },
            )
        }
        return document.copy(
            tracks = tracks,
            routes = document.routes.mapIndexed { index, route -> route.copy(id = routeIds[index]) },
            waypoints = document.waypoints.mapIndexed { index, point -> point.copy(id = waypointIds[index]) },
        )
    }

    private fun validateEntries(zip: ZipFile) {
        val names = mutableSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || entry.name !in ALLOWED_ENTRIES || !names.add(entry.name)) {
                throw ProjectFormatException("Unexpected or duplicate project entry '${entry.name}'")
            }
        }
        if (names != ALLOWED_ENTRIES) throw ProjectFormatException("Project archive is incomplete")
        val mime = zip.getInputStream(zip.getEntry(MIMETYPE_ENTRY)).use { it.readLimited(256).toString(Charsets.UTF_8) }
        if (mime != PROJECT_MIME_TYPE) throw ProjectFormatException("Unsupported project archive type")
    }

    private fun readManifest(zip: ZipFile): JSONObject {
        val bytes = zip.getInputStream(zip.getEntry(MANIFEST_ENTRY)).use { it.readLimited(MAX_MANIFEST_BYTES) }
        val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
        if (manifest.optString("format") != PROJECT_FORMAT) throw ProjectFormatException("Unsupported project format")
        val version = manifest.optInt("schemaVersion", -1)
        if (version > PROJECT_SCHEMA_VERSION) throw ProjectFormatException("Project was created by a newer VeloGPX version")
        if (version < 1) throw ProjectFormatException("Unsupported project schema version $version")
        return ProjectMigrations.migrate(manifest)
    }

    private fun archiveEntry(name: String) = ZipEntry(name).apply { time = 0L }

    companion object {
        const val MIMETYPE_ENTRY = "mimetype"
        const val GPX_ENTRY = "document.gpx"
        const val MANIFEST_ENTRY = "manifest.json"
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_GPX_BYTES = 32L * 1024L * 1024L
        private val ALLOWED_ENTRIES = setOf(MIMETYPE_ENTRY, GPX_ENTRY, MANIFEST_ENTRY)
    }
}

internal object ProjectJson {
    fun encodeManifest(project: ProjectState, gpxSha256: String, gpxByteLength: Long): JSONObject = JSONObject().apply {
        put("format", PROJECT_FORMAT)
        put("schemaVersion", PROJECT_SCHEMA_VERSION)
        put("projectId", project.id)
        put("title", project.title)
        put("revision", project.revision)
        put("documentRevision", project.documentRevision)
        put("lastExportedDocumentRevision", project.lastExportedDocumentRevision ?: JSONObject.NULL)
        put("createdAt", project.createdAt.toString())
        put("updatedAt", project.updatedAt.toString())
        put("document", JSONObject().apply {
            put("entry", ProjectArchiveCodec.GPX_ENTRY)
            put("sha256", gpxSha256)
            put("byteLength", gpxByteLength)
            put("gpxVersion", project.document.version.value)
            put("trackIdsInGpxOrder", JSONArray(project.document.tracks.map { it.id }))
            put("routeIdsInGpxOrder", JSONArray(project.document.routes.map { it.id }))
            put("waypointIdsInGpxOrder", JSONArray(project.document.waypoints.map { it.id }))
            put("segmentIdsByTrack", JSONArray(project.document.tracks.map { track -> JSONArray(track.segments.map { it.id }) }))
        })
        put("editor", encodeEditor(project.editor))
        put("summary", JSONObject().apply {
            put("trackCount", project.document.tracks.size)
            put("routeCount", project.document.routes.size)
            put("waypointCount", project.document.waypoints.size)
            put("pointCount", project.document.pointCount)
        })
    }

    fun decodeState(json: JSONObject, document: GpxDocument): ProjectState = ProjectState(
        id = json.getString("projectId"),
        title = json.getString("title"),
        document = document,
        editor = decodeEditor(json.optJSONObject("editor") ?: JSONObject()),
        revision = json.getLong("revision"),
        documentRevision = json.getLong("documentRevision"),
        lastExportedDocumentRevision = json.nullableLong("lastExportedDocumentRevision"),
        createdAt = Instant.parse(json.getString("createdAt")),
        updatedAt = Instant.parse(json.getString("updatedAt")),
    )

    fun decodeSummary(json: JSONObject, lastOpenedAt: Instant? = null): ProjectSummary {
        val summary = json.optJSONObject("summary") ?: JSONObject()
        val updated = Instant.parse(json.getString("updatedAt"))
        return ProjectSummary(
            id = json.getString("projectId"),
            title = json.getString("title"),
            revision = json.getLong("revision"),
            documentRevision = json.getLong("documentRevision"),
            createdAt = Instant.parse(json.getString("createdAt")),
            updatedAt = updated,
            lastOpenedAt = lastOpenedAt ?: updated,
            trackCount = summary.optInt("trackCount"),
            routeCount = summary.optInt("routeCount"),
            waypointCount = summary.optInt("waypointCount"),
            pointCount = summary.optInt("pointCount"),
        )
    }

    private fun encodeEditor(editor: ProjectEditorState) = JSONObject().apply {
        put("layerOrder", JSONArray(editor.layerOrder))
        put("styles", JSONObject().apply {
            editor.styles.forEach { (id, style) ->
                put(id, JSONObject().apply {
                    put("colorArgb", "%08X".format(style.color and 0xFFFFFFFFL))
                    put("visible", style.visible)
                    put("widthDp", style.widthDp.toDouble())
                })
            }
        })
        put("groups", JSONArray(editor.groups.map { group -> JSONObject().apply {
            put("id", group.id); put("name", group.name); put("layerIds", JSONArray(group.layerIds)); put("collapsed", group.collapsed)
        } }))
        put("selectedTrackId", editor.selectedTrackId ?: JSONObject.NULL)
        put("selectedTrackIds", JSONArray(editor.selectedTrackIds))
        put("selectedPoint", editor.selectedPoint?.let { selection -> JSONObject().apply {
            put("trackId", selection.trackId); put("segmentIndex", selection.segmentIndex); put("pointIndex", selection.pointIndex)
            put("latitudeE7", selection.latitudeE7); put("longitudeE7", selection.longitudeE7)
            put("timeEpochMillis", selection.timeEpochMillis ?: JSONObject.NULL)
        } } ?: JSONObject.NULL)
        put("camera", editor.camera?.let { camera -> JSONObject().apply {
            put("latitude", camera.latitude); put("longitude", camera.longitude); put("zoom", camera.zoom)
            put("bearing", camera.bearing); put("pitch", camera.pitch)
        } } ?: JSONObject.NULL)
        put("routingProfileId", editor.routingProfileId)
        put("panelId", editor.panelId)
        put("layersScrollIndex", editor.layersScrollIndex)
        put("layersScrollOffset", editor.layersScrollOffset)
    }

    private fun decodeEditor(json: JSONObject): ProjectEditorState {
        val stylesObject = json.optJSONObject("styles") ?: JSONObject()
        val styles = buildMap {
            val keys = stylesObject.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val value = stylesObject.getJSONObject(id)
                val color = value.optString("colorArgb", "FF176B45").removePrefix("#").toLong(16)
                put(id, TrackStyle(color, value.optBoolean("visible", true), value.optDouble("widthDp", 5.0).toFloat()))
            }
        }
        val groups = (json.optJSONArray("groups") ?: JSONArray()).objects().map { value ->
            ProjectLayerGroup(
                id = value.getString("id"),
                name = value.getString("name"),
                layerIds = value.stringList("layerIds"),
                collapsed = value.optBoolean("collapsed", false),
            )
        }
        val selection = json.nullableObject("selectedPoint")?.let { value ->
            ProjectSelection(
                trackId = value.getString("trackId"),
                segmentIndex = value.getInt("segmentIndex"),
                pointIndex = value.getInt("pointIndex"),
                latitudeE7 = value.getLong("latitudeE7"),
                longitudeE7 = value.getLong("longitudeE7"),
                timeEpochMillis = value.nullableLong("timeEpochMillis"),
            )
        }
        val camera = json.nullableObject("camera")?.let { value ->
            ProjectCamera(
                latitude = value.getDouble("latitude"), longitude = value.getDouble("longitude"), zoom = value.getDouble("zoom"),
                bearing = value.optDouble("bearing", 0.0), pitch = value.optDouble("pitch", 0.0),
            ).takeIf { it.latitude.isFinite() && it.latitude in -90.0..90.0 && it.longitude.isFinite() && it.longitude in -180.0..180.0 && it.zoom.isFinite() }
        }
        val selectedTrackId = json.nullableString("selectedTrackId")
        val selectedTrackIds = if (json.has("selectedTrackIds")) {
            json.stringList("selectedTrackIds")
        } else {
            selectedTrackId?.let(::listOf).orEmpty()
        }
        return ProjectEditorState(
            layerOrder = json.stringList("layerOrder"),
            styles = styles,
            groups = groups,
            selectedTrackId = selectedTrackId,
            selectedTrackIds = selectedTrackIds,
            selectedPoint = selection,
            camera = camera,
            routingProfileId = json.optString("routingProfileId", "trekking"),
            panelId = json.optString("panelId", "MAP"),
            layersScrollIndex = json.optInt("layersScrollIndex", 0).coerceAtLeast(0),
            layersScrollOffset = json.optInt("layersScrollOffset", 0).coerceAtLeast(0),
        )
    }
}

private class NonClosingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    override fun close() = flush()
}

private class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    var count: Long = 0; private set
    override fun write(value: Int) { out.write(value); count++ }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { out.write(buffer, offset, length); count += length }
}

private class CountingInputStream(input: InputStream, private val maximum: Long) : FilterInputStream(input) {
    var count: Long = 0; private set
    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) add(it.toLong()) }
    private fun add(value: Long) { count += value; if (count > maximum) throw ProjectFormatException("GPX entry exceeds the project size limit") }
}

private fun InputStream.readLimited(maximum: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var count = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        count += read
        if (count > maximum) throw ProjectFormatException("Project entry exceeds its size limit")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
private fun JSONArray.strings() = List(length()) { getString(it) }
private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
private fun JSONObject.stringList(name: String) = (optJSONArray(name) ?: JSONArray()).strings()
private fun JSONObject.nullableString(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
private fun JSONObject.nullableLong(name: String): Long? = if (!has(name) || isNull(name)) null else getLong(name)
private fun JSONObject.nullableObject(name: String): JSONObject? = if (!has(name) || isNull(name)) null else getJSONObject(name)

private fun ProjectState.sanitizeEditorReferences(): ProjectState {
    val trackIds = document.tracks.map { it.id }
    val valid = trackIds.toSet()
    val order = editor.layerOrder.filter { it in valid }.distinct() + trackIds.filterNot { it in editor.layerOrder }
    val selectedTrack = editor.selectedTrackId?.takeIf { it in valid }
    val selectedPoint = editor.selectedPoint?.takeIf { selection ->
        if (selection.trackId !in valid) return@takeIf false
        val point = document.tracks.first { it.id == selection.trackId }.segments
            .getOrNull(selection.segmentIndex)?.points?.getOrNull(selection.pointIndex) ?: return@takeIf false
        kotlin.math.abs((point.latitude * 10_000_000).toLong() - selection.latitudeE7) <= 1 &&
            kotlin.math.abs((point.longitude * 10_000_000).toLong() - selection.longitudeE7) <= 1 &&
            (selection.timeEpochMillis == null || point.time?.toEpochMilli() == selection.timeEpochMillis)
    }
    return copy(editor = editor.copy(
        layerOrder = order,
        styles = editor.styles.filterKeys { it in valid },
        groups = editor.groups.map { it.copy(layerIds = it.layerIds.filter { id -> id in valid }.distinct()) },
        selectedTrackId = selectedTrack,
        selectedTrackIds = editor.selectedTrackIds.filter { it in valid }.distinct(),
        selectedPoint = selectedPoint,
    ))
}
