package ch.cld9.velogpx

import android.app.Application
import org.maplibre.android.MapLibre

class VeloGpxApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
    }
}

