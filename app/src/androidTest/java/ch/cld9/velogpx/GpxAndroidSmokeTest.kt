package ch.cld9.velogpx

import androidx.test.ext.junit.runners.AndroidJUnit4
import ch.cld9.velogpx.io.GpxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class GpxAndroidSmokeTest {
    @Test fun parserWorksOnAndroidRuntime() {
        val source = """<gpx xmlns="http://www.topografix.com/GPX/1/1" version="1.1" creator="test"><trk><trkseg><trkpt lat="47" lon="8"/></trkseg></trk></gpx>"""
        val result = GpxParser().parse(ByteArrayInputStream(source.toByteArray()))
        assertTrue(result.issues.toString(), result.isSuccess)
        assertEquals(1, result.document!!.pointCount)
    }

    @Test fun externalEntityIsRejectedOnAndroidRuntime() {
        val source = """<!DOCTYPE gpx [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><gpx version="1.1" creator="x"><metadata><name>&xxe;</name></metadata></gpx>"""
        val result = GpxParser().parse(ByteArrayInputStream(source.toByteArray()))
        assertNull(result.document)
    }
}
