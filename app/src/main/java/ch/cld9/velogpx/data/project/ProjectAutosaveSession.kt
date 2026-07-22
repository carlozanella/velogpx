package ch.cld9.velogpx.data.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.min

sealed interface ProjectSaveStatus {
    data class Saved(val revision: Long) : ProjectSaveStatus
    data class Pending(val revision: Long) : ProjectSaveStatus
    data class Saving(val revision: Long) : ProjectSaveStatus
    data class Error(val revision: Long, val message: String) : ProjectSaveStatus
}

class ProjectAutosaveSession internal constructor(
    private val repository: ProjectRepository,
    initial: ProjectState,
    private val scope: CoroutineScope,
    private val debounceMillis: Long,
    private val maximumDelayMillis: Long,
) {
    private val stateLock = Any()
    private val saveMutex = Mutex()
    private var latest = initial
    private var generation = 0L
    private var persistedGeneration = 0L
    private var firstPendingAtNanos: Long? = null
    private var scheduled: Job? = null
    private var closed = false
    private val _status = MutableStateFlow<ProjectSaveStatus>(ProjectSaveStatus.Saved(initial.revision))
    val status: StateFlow<ProjectSaveStatus> = _status.asStateFlow()

    init {
        require(debounceMillis >= 0 && maximumDelayMillis >= debounceMillis)
        repository.registerAutosave(this)
    }

    fun submit(project: ProjectState) {
        val delayMillis: Long
        val target: Long
        synchronized(stateLock) {
            check(!closed) { "Autosave session is closed" }
            require(project.id == latest.id) { "Cannot submit another project to this autosave session" }
            latest = project
            generation++
            target = generation
            if (firstPendingAtNanos == null) firstPendingAtNanos = System.nanoTime()
            val elapsed = (System.nanoTime() - firstPendingAtNanos!!) / 1_000_000L
            delayMillis = min(debounceMillis, (maximumDelayMillis - elapsed).coerceAtLeast(0L))
            _status.value = ProjectSaveStatus.Pending(project.revision)
            scheduled?.cancel()
            scheduled = scope.launch {
                delay(delayMillis)
                persistAtLeast(target)
            }
        }
    }

    suspend fun flush(): ProjectState {
        val target = synchronized(stateLock) {
            scheduled?.cancel()
            generation
        }
        persistAtLeast(target)
        return synchronized(stateLock) { latest }
    }

    suspend fun close(): ProjectState {
        val saved = flush()
        synchronized(stateLock) { closed = true; scheduled?.cancel() }
        repository.unregisterAutosave(this)
        return saved
    }

    internal suspend fun backgroundFlush() {
        runCatching { flush() }
    }

    private suspend fun persistAtLeast(targetGeneration: Long) {
        withContext(NonCancellable) {
            saveMutex.withLock {
                while (true) {
                    val pending = synchronized(stateLock) {
                        if (persistedGeneration >= targetGeneration) return@withLock
                        generation to latest
                    }
                    _status.value = ProjectSaveStatus.Saving(pending.second.revision)
                    val saved = try {
                        repository.save(pending.second)
                    } catch (error: Throwable) {
                        _status.value = ProjectSaveStatus.Error(
                            pending.second.revision,
                            error.message ?: "Autosave failed",
                        )
                        throw error
                    }
                    run {
                        synchronized(stateLock) {
                            persistedGeneration = maxOf(persistedGeneration, pending.first)
                            if (generation == pending.first) {
                                latest = saved
                                firstPendingAtNanos = null
                            }
                        }
                        _status.value = ProjectSaveStatus.Saved(saved.revision)
                    }
                    if (pending.first >= targetGeneration) return@withLock
                }
            }
        }
    }
}
