package ch.cld9.velogpx

import android.app.Application
import android.content.ComponentCallbacks2
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import ch.cld9.velogpx.data.project.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre

class VeloGpxApplication : Application() {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repositoryDelegate = lazy { ProjectRepository(this) }
    val projectRepository: ProjectRepository get() = repositoryDelegate.value

    override fun onCreate() {
        super.onCreate()
        MapLibre.getInstance(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = flushProjects()
        })
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) flushProjects()
    }

    private fun flushProjects() {
        if (repositoryDelegate.isInitialized()) applicationScope.launch { projectRepository.flushAutosaves() }
    }
}
