package ch.cld9.velogpx.engine

import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxRoute
import java.security.MessageDigest

data class TrackDeduplicationResult(
    val novelTracks: List<GpxTrack>,
    val duplicateCount: Int,
)

/** Exact geometry identity used to make repeated GPX imports safe and predictable. */
object TrackDeduplicator {
    fun filterNovel(existing: Iterable<GpxTrack>, incoming: Iterable<GpxTrack>): TrackDeduplicationResult {
        val seen = existing.mapTo(mutableSetOf(), ::fingerprint)
        val novel = mutableListOf<GpxTrack>()
        var duplicates = 0
        incoming.forEach { track ->
            if (seen.add(fingerprint(track))) novel += track else duplicates++
        }
        return TrackDeduplicationResult(novel, duplicates)
    }

    fun fingerprint(track: GpxTrack): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.put(track.segments.size.toLong())
        track.segments.forEach { segment ->
            digest.put(segment.points.size.toLong())
            segment.points.forEach { point -> digest.putCoordinate(point) }
        }
        return digest.hex()
    }

    fun routeFingerprint(route: GpxRoute): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.put(route.points.size.toLong())
        route.points.forEach { point -> digest.putCoordinate(point) }
        return digest.hex()
    }

    /** Waypoints at the same coordinate remain distinct when their user-facing identity differs. */
    fun waypointFingerprint(point: GpxPoint): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.putCoordinate(point)
        digest.putString(point.name)
        digest.putString(point.symbol)
        digest.putString(point.type)
        digest.putString(point.description)
        return digest.hex()
    }

    private fun MessageDigest.putCoordinate(point: GpxPoint) {
        put((point.latitude * 10_000_000.0).toLong())
        put((GeoMath.normalizeLongitude(point.longitude) * 10_000_000.0).toLong())
    }

    private fun MessageDigest.put(value: Long) {
        for (shift in 56 downTo 0 step 8) update((value shr shift).toByte())
    }

    private fun MessageDigest.putString(value: String?) {
        val bytes = value.orEmpty().toByteArray(Charsets.UTF_8)
        put(bytes.size.toLong())
        update(bytes)
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
