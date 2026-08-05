package ch.cld9.velogpx.io

import ch.cld9.velogpx.model.GpxVersion
import ch.cld9.velogpx.model.XmlElement
import ch.cld9.velogpx.model.XmlText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CancellationException

class GpxCodecTest {
    @Test fun gpx11RoundTripPreservesHierarchyAndExtensionTree() {
        val source = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:gpxx="http://www.garmin.com/xmlschemas/GpxExtensions/v3" version="1.1" creator="Fixture">
              <metadata><name>EuroVelo &amp; Alps 🚲</name><time>2026-07-21T12:34:56.123Z</time></metadata>
              <wpt lat="47.1" lon="8.2"><name>Water &lt;tap&gt;</name></wpt>
              <trk><name>EV 5</name><trkseg>
                <trkpt lat="47.0" lon="8.0"><ele>400.5</ele><time>2026-07-21T10:00:00Z</time><extensions><gpxx:TrackExtension foo="bar"><gpxx:DisplayColor>Blue</gpxx:DisplayColor></gpxx:TrackExtension></extensions></trkpt>
                <trkpt lat="47.1" lon="8.1"><ele>420</ele></trkpt>
              </trkseg><trkseg><trkpt lat="47.2" lon="8.2"/></trkseg></trk>
              <rte><name>Alternative</name><rtept lat="47.3" lon="8.3"/></rte>
            </gpx>
        """.trimIndent()
        val parsed = GpxParser().parse(ByteArrayInputStream(source.toByteArray()))
        assertTrue(parsed.issues.toString(), parsed.isSuccess)
        assertEquals(1, parsed.document!!.tracks.size)
        assertEquals(2, parsed.document.tracks.single().segments.size)
        assertEquals(1, parsed.document.routes.size)
        assertEquals(1, parsed.document.waypoints.size)
        val output = ByteArrayOutputStream()
        GpxWriter().write(parsed.document, output)
        val reparsed = GpxParser().parse(ByteArrayInputStream(output.toByteArray()))
        assertTrue(reparsed.issues.toString(), reparsed.isSuccess)
        assertEquals("EuroVelo & Alps 🚲", reparsed.document!!.metadata!!.name)
        val extension = reparsed.document.tracks.single().segments.first().points.first().extensions.single()
        assertEquals("TrackExtension", extension.name.localName)
        assertEquals("http://www.garmin.com/xmlschemas/GpxExtensions/v3", extension.name.namespaceUri)
        assertEquals("bar", extension.attributes.single().value)
    }

    @Test fun gpx10CourseSpeedAndMetadataSurvive() {
        val source = """
            <gpx xmlns="http://www.topografix.com/GPX/1/0" version="1.0" creator="Legacy">
              <name>Old tour</name><author>Carlo</author><email>c@example.ch</email><url>https://example.ch</url><urlname>Source</urlname>
              <trk><name>Track</name><trkseg><trkpt lat="47" lon="8"><course>123.4</course><speed>5.5</speed><foo xmlns="urn:test">kept</foo></trkpt></trkseg></trk>
            </gpx>
        """.trimIndent()
        val document = GpxParser().parse(ByteArrayInputStream(source.toByteArray())).document!!
        assertEquals(GpxVersion.V1_0, document.version)
        assertEquals(123.4, document.tracks.single().segments.single().points.single().course!!, 0.0)
        val output = ByteArrayOutputStream()
        GpxWriter().write(document, output)
        val xml = output.toString(Charsets.UTF_8.name())
        assertTrue(xml.contains("<course>123.4</course>"))
        assertTrue(xml.contains("<foo xmlns=\"urn:test\">kept</foo>"))
        assertTrue(xml.indexOf("<time>").let { it < 0 } || xml.indexOf("<time>") < xml.indexOf("<course>"))
        assertTrue(xml.indexOf("<course>") < xml.indexOf("<speed>"))
        val reparsed = GpxParser().parse(ByteArrayInputStream(output.toByteArray())).document!!
        val author = reparsed.metadata!!.author!!
        assertEquals("Carlo", author.name)
        assertEquals("c", author.emailId)
    }

    @Test fun foreignElementsCollidingWithGpxNamesRemainExtensions() {
        val source = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" xmlns:x="urn:foreign" version="1.1" creator="x">
              <x:name>foreign root name</x:name>
              <trk><name>real name</name><trkseg><trkpt lat="47" lon="8"><extensions><x:link>foreign link</x:link></extensions></trkpt></trkseg></trk>
            </gpx>
        """.trimIndent()
        val document = GpxParser().parse(ByteArrayInputStream(source.toByteArray())).document!!
        assertEquals("real name", document.tracks.single().name)
        assertEquals("name", document.rootExtensions.single().name.localName)
        assertEquals("urn:foreign", document.rootExtensions.single().name.namespaceUri)
        assertEquals("link", document.tracks.single().segments.single().points.single().extensions.single().name.localName)
    }

    @Test fun writerUsesPlainDecimalsAndBindsInheritedAttributePrefixes() {
        val source = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="x">
              <trk><trkseg><trkpt lat="0.0000001" lon="8"><extensions xmlns:x="urn:attr"><foreign xmlns="urn:element" x:code="ok"/></extensions></trkpt></trkseg></trk>
            </gpx>
        """.trimIndent()
        val document = GpxParser().parse(ByteArrayInputStream(source.toByteArray())).document!!
        val output = ByteArrayOutputStream()
        GpxWriter().write(document, output)
        val xml = output.toString(Charsets.UTF_8.name())
        assertTrue(xml.contains("lat=\"0.0000001\""))
        assertFalse(xml.contains("E-"))
        assertTrue(xml.contains("xmlns:x=\"urn:attr\""))
        assertEquals("ok", GpxParser().parse(ByteArrayInputStream(output.toByteArray())).document!!
            .tracks.single().segments.single().points.single().extensions.single().attributes.single().value)
    }

    @Test fun extensionMixedTextAndElementsRetainTheirOrder() {
        val source = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="x">
              <trk><trkseg><trkpt lat="47" lon="8"><extensions><foreign xmlns="urn:mixed">before<child/>after</foreign></extensions></trkpt></trkseg></trk>
            </gpx>
        """.trimIndent()
        val document = GpxParser().parse(ByteArrayInputStream(source.toByteArray())).document!!
        val output = ByteArrayOutputStream()
        GpxWriter().write(document, output)
        val children = GpxParser().parse(ByteArrayInputStream(output.toByteArray())).document!!
            .tracks.single().segments.single().points.single().extensions.single().children
        assertEquals(3, children.size)
        assertEquals("before", (children[0] as XmlText).value)
        assertEquals("child", (children[1] as XmlElement).name.localName)
        assertEquals("after", (children[2] as XmlText).value)
    }

    @Test fun exportRecomputesStaleMetadataBounds() {
        val source = """
            <gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="x">
              <metadata><bounds minlat="0" minlon="0" maxlat="1" maxlon="1"/></metadata>
              <trk><trkseg><trkpt lat="47" lon="8"/><trkpt lat="48" lon="9"/></trkseg></trk>
            </gpx>
        """.trimIndent()
        val document = GpxParser().parse(ByteArrayInputStream(source.toByteArray())).document!!
        val output = ByteArrayOutputStream()
        GpxWriter().write(document, output)
        val bounds = GpxParser().parse(ByteArrayInputStream(output.toByteArray())).document!!.metadata!!.bounds!!
        assertEquals(47.0, bounds.minLatitude, 0.0)
        assertEquals(9.0, bounds.maxLongitude, 0.0)
    }

    @Test fun pointLimitIsEnforcedAsTracksAreParsed() {
        val source = buildString {
            append("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\" creator=\"test\">")
            repeat(4) { index ->
                append("<trk><trkseg><trkpt lat=\"${46 + index}.0\" lon=\"7.0\"/></trkseg></trk>")
            }
            append("</gpx>")
        }

        val result = GpxParser(GpxParser.Limits(maxPoints = 3)).parse(ByteArrayInputStream(source.toByteArray()))

        assertNull(result.document)
        assertTrue(result.issues.any { it.message.contains("Point limit") })
    }

    @Test fun interruptedParsingPropagatesCancellation() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) {
                GpxParser().parse(ByteArrayInputStream("<gpx version=\"1.1\"/>".toByteArray()))
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun denseProjectParsesWithoutPerPointIdentityBottleneck() {
        val source = buildString(4_000_000) {
            append("<gpx xmlns=\"http://www.topografix.com/GPX/1/1\" version=\"1.1\" creator=\"test\"><trk><trkseg>")
            repeat(50_000) { index ->
                append("<trkpt lat=\"47.0\" lon=\"")
                append(8.0 + index / 1_000_000.0)
                append("\"><ele>500</ele></trkpt>")
            }
            append("</trkseg></trk></gpx>")
        }

        val document = GpxParser().parse(ByteArrayInputStream(source.toByteArray())).document!!

        assertEquals(50_000, document.pointCount)
        val points = document.tracks.single().segments.single().points
        assertEquals(points.size, points.map { it.id }.toSet().size)
    }

    @Test fun invalidCoordinatesAreRejected() {
        val result = GpxParser().parse(ByteArrayInputStream("<gpx version=\"1.1\"><wpt lat=\"91\" lon=\"8\"/></gpx>".toByteArray()))
        assertNull(result.document)
        assertFalse(result.isSuccess)
    }

    @Test fun externalEntitiesAreRejected() {
        val source = """<!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><gpx version="1.1" creator="x"><metadata><name>&xxe;</name></metadata></gpx>"""
        val result = GpxParser().parse(ByteArrayInputStream(source.toByteArray()))
        assertNull(result.document)
        assertTrue(result.issues.any { it.code == "INVALID_GPX" })
    }
}
