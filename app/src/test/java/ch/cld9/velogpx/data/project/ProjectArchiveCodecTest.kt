package ch.cld9.velogpx.data.project

import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxRoute
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import ch.cld9.velogpx.model.TrackStyle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ProjectArchiveCodecTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test fun archiveRoundTripPreservesDocumentIdentitiesAndEditorState() {
        val waypoint = GpxPoint(47.1, 8.2, name = "Water", id = "waypoint-id")
        val route = GpxRoute(name = "Alternative", points = listOf(GpxPoint(47.2, 8.3)), id = "route-id")
        val firstSegment = GpxTrackSegment(listOf(GpxPoint(47.0, 8.0), GpxPoint(47.05, 8.05)), id = "segment-a")
        val secondSegment = GpxTrackSegment(listOf(GpxPoint(47.1, 8.1)), id = "segment-b")
        val track = GpxTrack(name = "EV 5", segments = listOf(firstSegment, secondSegment), id = "track-id")
        val document = GpxDocument(
            metadata = GpxMetadata(name = "Tour"), waypoints = listOf(waypoint), routes = listOf(route), tracks = listOf(track),
        )
        val now = Instant.parse("2026-07-22T00:00:00Z")
        val project = ProjectState(
            id = "b1c6508e-8817-4b47-8568-023c910d5690",
            title = "EuroVelo tour",
            document = document,
            editor = ProjectEditorState(
                layerOrder = listOf(track.id),
                styles = mapOf(track.id to TrackStyle(0xFF1565C0, visible = false, widthDp = 7f)),
                groups = listOf(ProjectLayerGroup("group-id", "France", listOf(track.id), collapsed = true)),
                selectedTrackId = track.id,
                selectedTrackIds = listOf(track.id),
                selectedPoint = ProjectSelection(track.id, 0, 1, 470500000, 80500000),
                camera = ProjectCamera(47.1, 8.2, 10.5, 15.0, 25.0),
                routingProfileId = "gravel",
                panelId = "LAYERS",
            ),
            revision = 12,
            documentRevision = 8,
            lastExportedDocumentRevision = 7,
            createdAt = now,
            updatedAt = now.plusSeconds(60),
        )
        val file = temporary.newFile("project.velogpx")
        FileOutputStream(file).use { ProjectArchiveCodec().write(project, it) }

        val loaded = ProjectArchiveCodec().read(file)

        assertEquals(project.id, loaded.id)
        assertEquals("track-id", loaded.document.tracks.single().id)
        assertEquals(listOf("segment-a", "segment-b"), loaded.document.tracks.single().segments.map { it.id })
        assertEquals("route-id", loaded.document.routes.single().id)
        assertEquals("waypoint-id", loaded.document.waypoints.single().id)
        assertEquals(project.editor, loaded.editor)
        assertEquals(project.revision, loaded.revision)
        assertEquals(project.documentRevision, loaded.documentRevision)
    }

    @Test fun checksumMismatchIsRejected() {
        val project = ProjectState.create(
            title = "Test",
            document = GpxDocument(tracks = listOf(GpxTrack(segments = listOf(GpxTrackSegment(listOf(GpxPoint(47.0, 8.0))))))),
            now = Instant.EPOCH,
            id = "5799f1e7-499a-432b-aaee-f8277b348ce5",
        )
        val original = temporary.newFile("original.velogpx")
        FileOutputStream(original).use { ProjectArchiveCodec().write(project, it) }
        val corrupt = temporary.newFile("corrupt.velogpx")
        rewriteManifest(original, corrupt) { it.getJSONObject("document").put("sha256", "00".repeat(32)); it }

        assertThrows(ProjectFormatException::class.java) { ProjectArchiveCodec().read(corrupt) }
    }

    @Test fun legacyV1ManifestDefaultsMultiSelectionFromSelectedTrack() {
        val track = GpxTrack(
            name = "EV 15",
            segments = listOf(GpxTrackSegment(listOf(GpxPoint(47.0, 8.0)))),
            id = "legacy-selected-track",
        )
        val project = ProjectState.create(
            title = "Legacy v1",
            document = GpxDocument(tracks = listOf(track)),
            now = Instant.EPOCH,
            id = "e9ee3ec0-334b-4db1-8340-b4e305e920e3",
        )
        val original = temporary.newFile("legacy-v1-original.velogpx")
        FileOutputStream(original).use { ProjectArchiveCodec().write(project, it) }
        val legacy = temporary.newFile("legacy-v1.velogpx")
        rewriteManifest(original, legacy) { manifest ->
            manifest.getJSONObject("editor").remove("selectedTrackIds")
            manifest
        }

        val loaded = ProjectArchiveCodec().read(legacy)

        assertEquals(listOf(track.id), loaded.editor.selectedTrackIds)
    }

    private fun rewriteManifest(source: File, target: File, transform: (JSONObject) -> JSONObject) {
        ZipFile(source).use { input ->
            ZipOutputStream(FileOutputStream(target)).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    output.putNextEntry(ZipEntry(entry.name))
                    if (entry.name == ProjectArchiveCodec.MANIFEST_ENTRY) {
                        val json = JSONObject(input.getInputStream(entry).bufferedReader().readText())
                        output.write(transform(json).toString().toByteArray())
                    } else input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
    }
}
