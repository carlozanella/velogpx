package ch.cld9.velogpx.share

import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ch.cld9.velogpx.io.GpxParser
import ch.cld9.velogpx.io.GpxWriter
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxRoute
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GpxShareServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preparedShares = mutableListOf<Pair<GpxShareService, PreparedGpxShare>>()

    @After fun cleanPreparedShares() {
        preparedShares.forEach { (service, prepared) -> service.discard(prepared) }
        preparedShares.clear()
    }

    @Test fun selectedTrackFileIsReadableGpx11WithNoUnselectedGeometry() {
        val service = service(resolves = false)
        val prepared = trackPrepared(service, " ../My EV5?:route.GPX ")
        val file = prepared.files.single()

        assertEquals("My EV5_route.gpx", file.displayName)
        assertEquals(GPX_MIME_TYPE, context.contentResolver.getType(file.uri))
        val parsed = file.cacheFile.inputStream().use { GpxParser().parse(it, file.displayName) }
        assertTrue(parsed.issues.toString(), parsed.isSuccess)
        assertEquals("1.1", parsed.document!!.version.value)
        assertEquals(listOf("Selected route"), parsed.document.tracks.map { it.name })
        assertEquals(1, parsed.document.tracks.single().segments.size)
        assertEquals(
            listOf(47.0, 48.0),
            parsed.document.tracks.single().segments.single().points.map { it.latitude },
        )
        assertTrue(parsed.document.routes.isEmpty())
        assertTrue(parsed.document.waypoints.isEmpty())
    }

    @Test fun wholeProjectFileSerializesEveryPathIntoOneGarminCourse() {
        val service = service(resolves = false)
        val document = fixture()
        val prepared = remember(service, service.prepare(GpxShareRequest.Document(document)))

        val parsed = prepared.files.single().cacheFile.inputStream().use { GpxParser().parse(it).document!! }
        assertEquals(1, parsed.tracks.size)
        assertEquals(1, parsed.tracks.single().segments.size)
        assertEquals(
            listOf(47.0, 48.0, 49.0, 46.5),
            parsed.tracks.single().segments.single().points.map { it.latitude },
        )
        assertEquals(1, parsed.waypoints.size)
        assertTrue(parsed.routes.isEmpty())
        assertEquals(document.pointCount, parsed.pointCount)
    }

    @Test fun chooserIntentGrantsOneUriAndCarriesGpxMimeType() {
        val service = service(resolves = false)
        val prepared = trackPrepared(service)
        val launch = service.createShareIntent(prepared, preferGarminConnect = true)

        assertEquals(GpxShareDestination.SYSTEM_CHOOSER, launch.destination)
        assertEquals(Intent.ACTION_CHOOSER, launch.intent.action)
        val send = chooserTarget(launch.intent)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(GPX_MIME_TYPE, send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(1, send.clipData!!.itemCount)
        assertEquals(prepared.files.single().uri, send.clipData!!.getItemAt(0).uri)
        assertNotNull(send.extras?.get(Intent.EXTRA_STREAM))
    }

    @Test fun multipleFilesUseSendMultipleAndUniqueNames() {
        val service = service(resolves = false)
        val document = fixture()
        val prepared = remember(
            service,
            service.prepare(
                listOf(
                    GpxShareRequest.Track(document, "selected", "Route.gpx"),
                    GpxShareRequest.Track(document, "other", "Route.gpx"),
                ),
            ),
        )
        val send = chooserTarget(service.createShareIntent(prepared, preferGarminConnect = false).intent)

        assertEquals(listOf("Route.gpx", "Route (2).gpx"), prepared.files.map { it.displayName })
        assertEquals(Intent.ACTION_SEND_MULTIPLE, send.action)
        assertEquals(2, send.clipData!!.itemCount)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun resolvableGarminIntentIsPreferredWithoutWrappingInChooser() {
        val service = service(resolves = true)
        val prepared = trackPrepared(service)
        val launch = service.createShareIntent(prepared)

        assertEquals(GpxShareDestination.GARMIN_CONNECT, launch.destination)
        assertEquals(Intent.ACTION_SEND, launch.intent.action)
        assertEquals(GPX_MIME_TYPE, launch.intent.type)
        assertEquals(GpxShareService.GARMIN_CONNECT_PACKAGE, launch.intent.`package`)
        assertTrue(launch.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(launch.intent.action == Intent.ACTION_CHOOSER)
    }

    @Test fun garminOpenUsesViewContractWithContentUriAndReadGrant() {
        val service = service(resolves = true)
        val prepared = trackPrepared(service)
        val launch = service.createOpenIntent(prepared)

        assertEquals(GpxShareDestination.GARMIN_CONNECT, launch.destination)
        assertEquals(Intent.ACTION_VIEW, launch.intent.action)
        assertEquals(GPX_MIME_TYPE, launch.intent.type)
        assertEquals(prepared.files.single().uri, launch.intent.data)
        assertEquals(GpxShareService.GARMIN_CONNECT_PACKAGE, launch.intent.`package`)
        assertTrue(launch.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(prepared.files.single().uri, launch.intent.clipData!!.getItemAt(0).uri)
    }

    @Test fun unresolvedGarminOpenFallsBackToViewChooserNotShareSheet() {
        val service = service(resolves = false)
        val prepared = trackPrepared(service)
        val launch = service.createOpenIntent(prepared)
        val target = chooserTarget(launch.intent)

        assertEquals(GpxShareDestination.SYSTEM_CHOOSER, launch.destination)
        assertEquals(Intent.ACTION_CHOOSER, launch.intent.action)
        assertEquals(Intent.ACTION_VIEW, target.action)
        assertEquals(prepared.files.single().uri, target.data)
        assertEquals(GPX_MIME_TYPE, target.type)
    }

    @Test fun segmentExportWritesOnlyTheRequestedSegment() {
        val service = service(resolves = false)
        val document = fixture()
        val segment = document.tracks.first { it.id == "selected" }.segments.last()
        val prepared = remember(
            service,
            service.prepare(GpxShareRequest.Segment(document, "selected", segment.id)),
        )

        val parsed = prepared.files.single().cacheFile.inputStream().use { GpxParser().parse(it).document!! }
        assertEquals(1, parsed.tracks.single().segments.size)
        assertEquals(48.0, parsed.tracks.single().segments.single().points.single().latitude, 0.0)
    }

    private fun service(resolves: Boolean) = GpxShareService(
        context = context,
        writer = GpxWriter(),
        activityResolver = ShareActivityResolver { intent ->
            resolves && intent.`package` == GpxShareService.GARMIN_CONNECT_PACKAGE
        },
    )

    private fun trackPrepared(service: GpxShareService, name: String? = null): PreparedGpxShare =
        remember(service, service.prepare(GpxShareRequest.Track(fixture(), "selected", name)))

    private fun remember(service: GpxShareService, prepared: PreparedGpxShare): PreparedGpxShare {
        preparedShares += service to prepared
        return prepared
    }

    @Suppress("DEPRECATION")
    private fun chooserTarget(chooser: Intent): Intent = if (Build.VERSION.SDK_INT >= 33) {
        chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
    } else {
        chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!
    }

    private fun fixture() = GpxDocument(
        metadata = GpxMetadata(name = "Whole assembly"),
        waypoints = listOf(GpxPoint(46.0, 7.0, name = "POI")),
        routes = listOf(GpxRoute(name = "Unselected GPX route", points = listOf(GpxPoint(46.5, 7.5)))),
        tracks = listOf(
            GpxTrack(
                id = "selected",
                name = "Selected route",
                segments = listOf(
                    GpxTrackSegment(id = "first", points = listOf(GpxPoint(47.0, 8.0))),
                    GpxTrackSegment(id = "second", points = listOf(GpxPoint(48.0, 9.0))),
                ),
            ),
            GpxTrack(
                id = "other",
                name = "Not selected",
                segments = listOf(GpxTrackSegment(points = listOf(GpxPoint(49.0, 10.0)))),
            ),
        ),
    )
}
