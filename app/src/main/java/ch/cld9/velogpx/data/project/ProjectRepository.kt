package ch.cld9.velogpx.data.project

import android.content.Context
import ch.cld9.velogpx.io.GpxParser
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxMetadata
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

class ProjectRepository private constructor(
    private val rootDirectory: File,
    private val legacyAutosave: File,
    private val atomicFiles: AtomicFileAccess,
    private val clock: ProjectClock,
    private val dispatcher: CoroutineDispatcher,
) {
    constructor(context: Context) : this(
        rootDirectory = File(context.noBackupFilesDir, "velogpx"),
        legacyAutosave = File(context.filesDir, "projects/autosave.gpx"),
        atomicFiles = AndroidAtomicFileAccess(),
        clock = ProjectClock(Instant::now),
        dispatcher = Dispatchers.IO,
    )

    private val projectsDirectory = File(rootDirectory, "projects")
    private val trashDirectory = File(rootDirectory, "trash")
    private val legacyDirectory = File(rootDirectory, "legacy")
    private val catalogStore = ProjectCatalogStore(File(rootDirectory, "catalog.json"), atomicFiles)
    private val fileStore = ProjectFileStore(projectsDirectory, atomicFiles, clock = clock)
    private val mutex = Mutex()
    private val autosaves = java.util.Collections.synchronizedSet(mutableSetOf<ProjectAutosaveSession>())
    private val _catalog = MutableStateFlow(ProjectCatalog())
    val catalog: StateFlow<ProjectCatalog> = _catalog.asStateFlow()

    suspend fun openLastOrCreate(): ProjectOpenResult = ioLocked {
        ensureDirectories()
        var catalog = reconcile(readCatalog())
        var legacyResult: ProjectOpenResult? = null
        if (catalog.projects.isEmpty() && legacyAutosave.isFile) {
            legacyResult = migrateLegacyLocked()
            if (legacyResult != null) catalog = reconcile(readCatalog())
        }
        if (catalog.projects.isEmpty()) {
            val created = createLocked("Untitled bicycle tour", GpxDocument(metadata = GpxMetadata(name = "Untitled bicycle tour")))
            catalog = readCatalog()
            return@ioLocked ProjectOpenResult(created, ProjectRecoverySource.NEW_PROJECT)
        }
        legacyResult?.let { return@ioLocked it }
        val targetId = catalog.lastProjectId?.takeIf { id -> catalog.projects.any { it.id == id } }
            ?: catalog.projects.maxByOrNull(ProjectSummary::lastOpenedAt)?.id
            ?: error("Project catalog has no usable project")
        openLocked(targetId)
    }

    suspend fun listProjects(): List<ProjectSummary> = ioLocked {
        val reconciled = reconcile(readCatalog())
        reconciled.projects.sortedByDescending(ProjectSummary::lastOpenedAt)
    }

    suspend fun create(
        title: String,
        document: GpxDocument = GpxDocument(metadata = GpxMetadata(name = title)),
    ): ProjectState = ioLocked { createLocked(title, document) }

    suspend fun open(projectId: String): ProjectOpenResult = ioLocked { openLocked(projectId) }

    suspend fun save(project: ProjectState, snapshotPolicy: SnapshotPolicy = SnapshotPolicy.AUTOMATIC): ProjectState = ioLocked {
        saveLocked(project, snapshotPolicy)
    }

    suspend fun rename(projectId: String, title: String): ProjectState = ioLocked {
        require(title.isNotBlank()) { "Project title must not be blank" }
        val current = fileStore.read(projectId).project
        saveLocked(current.nextRevision(title = title.trim(), documentChanged = false, now = clock.now()), SnapshotPolicy.NONE)
    }

    suspend fun duplicate(projectId: String, title: String? = null): ProjectState = ioLocked {
        val source = fileStore.read(projectId).project
        val now = clock.now()
        val duplicate = source.copy(
            id = UUID.randomUUID().toString(),
            title = title?.trim()?.takeIf(String::isNotEmpty) ?: "${source.title} copy",
            revision = 1,
            documentRevision = 1,
            lastExportedDocumentRevision = null,
            createdAt = now,
            updatedAt = now,
        )
        fileStore.write(duplicate, SnapshotPolicy.FORCE)
        val catalog = readCatalog()
        writeCatalog(catalog.copy(projects = catalog.projects + duplicate.summary(now)))
        duplicate
    }

    suspend fun moveToTrash(projectId: String) = ioLocked {
        val catalog = readCatalog()
        val suffix = clock.now().toEpochMilli()
        fileStore.moveTo(projectId, File(trashDirectory, "$projectId-$suffix"))
        val remaining = catalog.projects.filterNot { it.id == projectId }
        writeCatalog(
            catalog.copy(
                lastProjectId = catalog.lastProjectId?.takeUnless { it == projectId }
                    ?: remaining.maxByOrNull(ProjectSummary::lastOpenedAt)?.id,
                projects = remaining,
            ),
        )
    }

    suspend fun createSnapshot(projectId: String): File = ioLocked { fileStore.createSnapshot(projectId) }

    suspend fun listSnapshots(projectId: String): List<File> = ioLocked { fileStore.snapshots(projectId) }

    suspend fun restoreSnapshot(projectId: String, snapshot: File): ProjectState = ioLocked {
        val restored = fileStore.restoreSnapshot(projectId, snapshot)
        val catalog = readCatalog()
        val lastOpened = catalog.projects.firstOrNull { it.id == projectId }?.lastOpenedAt ?: clock.now()
        writeCatalog(catalog.withSummary(restored.summary(lastOpened)))
        restored
    }

    fun autosaveSession(
        initial: ProjectState,
        scope: kotlinx.coroutines.CoroutineScope,
        debounceMillis: Long = 750,
        maximumDelayMillis: Long = 5_000,
    ) = ProjectAutosaveSession(this, initial, scope, debounceMillis, maximumDelayMillis)

    suspend fun flushAutosaves() {
        val sessions = synchronized(autosaves) { autosaves.toList() }
        sessions.forEach { it.backgroundFlush() }
    }

    internal fun registerAutosave(session: ProjectAutosaveSession) { autosaves += session }
    internal fun unregisterAutosave(session: ProjectAutosaveSession) { autosaves -= session }

    private fun openLocked(projectId: String): ProjectOpenResult {
        val stored = fileStore.read(projectId)
        val now = clock.now()
        val catalog = reconcile(readCatalog())
        writeCatalog(catalog.copy(
            lastProjectId = projectId,
            projects = catalog.projects.filterNot { it.id == projectId } + stored.project.summary(now),
        ))
        return ProjectOpenResult(stored.project, stored.source, stored.warnings)
    }

    private fun createLocked(title: String, document: GpxDocument): ProjectState {
        val now = clock.now()
        val project = ProjectState.create(title.trim().ifBlank { "Untitled bicycle tour" }, document, now)
        fileStore.write(project, SnapshotPolicy.FORCE)
        val catalog = readCatalog()
        writeCatalog(catalog.copy(lastProjectId = project.id, projects = catalog.projects + project.summary(now)))
        return project
    }

    private fun saveLocked(project: ProjectState, snapshotPolicy: SnapshotPolicy): ProjectState {
        val durableRevision = runCatching { fileStore.readSummary(project.id).revision }.getOrNull()
        require(durableRevision == null || project.revision >= durableRevision) {
            "Refusing to overwrite project revision $durableRevision with stale revision ${project.revision}"
        }
        fileStore.write(project, snapshotPolicy)
        val catalog = readCatalog()
        val lastOpened = catalog.projects.firstOrNull { it.id == project.id }?.lastOpenedAt ?: clock.now()
        writeCatalog(catalog.withSummary(project.summary(lastOpened)))
        return project
    }

    private fun migrateLegacyLocked(): ProjectOpenResult? {
        val result = legacyAutosave.inputStream().buffered().use { GpxParser().parse(it, "Recovered project") }
        val document = result.document ?: return null
        val title = document.metadata?.name?.takeIf(String::isNotBlank) ?: "Recovered bicycle tour"
        val project = ProjectState.create(title, document, clock.now())
        fileStore.write(project, SnapshotPolicy.FORCE)
        val catalog = readCatalog()
        writeCatalog(catalog.copy(lastProjectId = project.id, projects = catalog.projects + project.summary(clock.now())))
        legacyDirectory.mkdirs()
        val target = File(legacyDirectory, "autosave-${clock.now().toEpochMilli()}.migrated.gpx")
        if (!legacyAutosave.renameTo(target)) {
            legacyAutosave.copyTo(target, overwrite = false)
            legacyAutosave.delete()
        }
        return ProjectOpenResult(
            project,
            ProjectRecoverySource.LEGACY_AUTOSAVE,
            result.issues.map { it.message },
        )
    }

    private fun reconcile(catalog: ProjectCatalog): ProjectCatalog {
        val existingById = catalog.projects.associateBy(ProjectSummary::id)
        val summaries = fileStore.projectIds().mapNotNull { id ->
            val previous = existingById[id]
            runCatching { fileStore.readSummary(id, previous?.lastOpenedAt) }.getOrElse { previous }
        }
        val last = catalog.lastProjectId?.takeIf { id -> summaries.any { it.id == id } }
        val reconciled = ProjectCatalog(lastProjectId = last, projects = summaries)
        if (reconciled != catalog) writeCatalog(reconciled) else _catalog.value = reconciled
        return reconciled
    }

    private fun readCatalog(): ProjectCatalog = runCatching { catalogStore.read() }.getOrElse { ProjectCatalog() }

    private fun writeCatalog(value: ProjectCatalog) {
        catalogStore.write(value)
        _catalog.value = value
    }

    private fun ensureDirectories() {
        rootDirectory.mkdirs(); projectsDirectory.mkdirs(); trashDirectory.mkdirs(); legacyDirectory.mkdirs()
    }

    private suspend fun <T> ioLocked(block: () -> T): T = withContext(dispatcher) { mutex.withLock { block() } }

    companion object {
        fun forTesting(
            rootDirectory: File,
            legacyAutosave: File = File(rootDirectory, "legacy-source/autosave.gpx"),
            clock: ProjectClock = ProjectClock(Instant::now),
            dispatcher: CoroutineDispatcher = Dispatchers.IO,
        ) = ProjectRepository(rootDirectory, legacyAutosave, JvmAtomicFileAccess(), clock, dispatcher)
    }
}

private fun ProjectCatalog.withSummary(summary: ProjectSummary) = copy(
    projects = projects.filterNot { it.id == summary.id } + summary,
)
