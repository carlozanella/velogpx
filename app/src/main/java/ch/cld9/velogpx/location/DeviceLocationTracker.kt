@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package ch.cld9.velogpx.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/** Foreground-only location source. Permission remains a UI decision. */
class DeviceLocationTracker(
    context: Context,
    private val onLocation: (Location) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private var started = false

    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (!hasPermission()) return false
        if (started) return true
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { provider -> runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            onError("Location is turned off. Enable it in Android settings and try again.")
            return false
        }
        try {
            providers.forEach { provider ->
                manager.getLastKnownLocation(provider)?.let(onLocation)
                manager.requestLocationUpdates(provider, 2_000L, 3f, listener, Looper.getMainLooper())
            }
            started = true
            return true
        } catch (_: SecurityException) {
            onError("Location permission is required to show your position.")
        } catch (error: RuntimeException) {
            onError("Could not start location: ${error.message ?: "Android location is unavailable"}")
        }
        return false
    }

    fun stop() {
        if (!started) return
        runCatching { manager.removeUpdates(listener) }
        started = false
    }
}
