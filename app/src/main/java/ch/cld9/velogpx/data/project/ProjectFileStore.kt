package ch.cld9.velogpx.data.project

import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

fun interface ProjectClock { fun now(): Instant }

data class StoredProjectResult(
    val project: ProjectState,
    val source: ProjectRecoverySource,
    val warnings: List<String> = emptyList(),
)

class ProjectFileStore(
    private val projectsDirectory: File,
    private val atomicFiles: AtomicFileAccess,
    private val codec: ProjectArchiveCodec = ProjectArchiveCodec(),
    private val clock: ProjectClock = ProjectClock(Instant::now),
) {
    fun ensureDirectories() = projectsDirectory.mkdirs().let { Unit }

    fun projectIds(): List<String> {
        ensureDirectories()
        return projectsDirectory.listFiles()
            ?.filter { it.isDirectory && runCatching { UUID.fromString(it.name) }.isSuccess }
            ?.map { it.name }
            .orEmpty()
    }

    fun currentFile(projectId: String): File = File(projectDirectory(projectId), CURRENT_NAME)

    fun write(project: ProjectState, snapshotPolicy: SnapshotPolicy = SnapshotPolicy.AUTOMATIC) {
        validateProjectId(project.id)
        val current = currentFile(project.id)
        atomicFiles.write(current) { codec.write(project, it) }
        if (snapshotPolicy == SnapshotPolicy.FORCE || snapshotPolicy == SnapshotPolicy.AUTOMATIC && shouldSnapshot(project)) {
            createSnapshot(project.id)
        }
    }

    fun read(projectId: String): StoredProjectResult {
        validateProjectId(projectId)
        val current = currentFile(projectId)
        if (current.isFile) {
            val currentResult = runCatching {
                atomicFiles.prepareRead(current)
                codec.read(current)
            }
            currentResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            currentResult.getOrNull()?.let { return StoredProjectResult(it, ProjectRecoverySource.CURRENT) }
            quarantine(current)
            val warning = currentResult.exceptionOrNull()?.message ?: "The current project archive is damaged"
            newestSnapshots(projectId).forEach { snapshot ->
                val snapshotResult = runCatching { codec.read(snapshot) }
                snapshotResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
                snapshotResult.getOrNull()?.let { recovered ->
                    atomicFiles.copy(snapshot, current)
                    return StoredProjectResult(
                        recovered,
                        ProjectRecoverySource.SNAPSHOT,
                        listOf("Recovered project revision ${recovered.revision} because $warning"),
                    )
                }
            }
            throw ProjectFormatException("No valid recovery snapshot exists for project $projectId: $warning")
        }
        newestSnapshots(projectId).forEach { snapshot ->
            val snapshotResult = runCatching { codec.read(snapshot) }
            snapshotResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            snapshotResult.getOrNull()?.let { recovered ->
                atomicFiles.copy(snapshot, current)
                return StoredProjectResult(recovered, ProjectRecoverySource.SNAPSHOT, listOf("Recovered a missing project archive from a snapshot."))
            }
        }
        throw ProjectFormatException("Project $projectId has no current archive")
    }

    fun readSummary(projectId: String, lastOpenedAt: Instant? = null): ProjectSummary {
        val current = currentFile(projectId)
        atomicFiles.prepareRead(current)
        return codec.readSummary(current, lastOpenedAt)
    }

    fun createSnapshot(projectId: String): File {
        val current = currentFile(projectId)
        atomicFiles.prepareRead(current)
        val summary = codec.readSummary(current)
        val snapshots = snapshotsDirectory(projectId).apply { mkdirs() }
        val timestamp = clock.now().toString().replace(Regex("[^0-9A-Za-z]"), "")
        val target = File(snapshots, "%020d-%s.velogpx".format(summary.revision, timestamp))
        if (!target.exists()) atomicFiles.copy(current, target)
        rotateSnapshots(projectId)
        return target
    }

    fun snapshots(projectId: String): List<File> = newestSnapshots(projectId)

    fun restoreSnapshot(projectId: String, snapshot: File): ProjectState {
        require(snapshot.canonicalFile.parentFile == snapshotsDirectory(projectId).canonicalFile) { "Snapshot is outside its project" }
        val current = read(projectId).project
        createSnapshot(projectId)
        val restored = codec.read(snapshot).copy(
            id = current.id,
            revision = current.revision + 1,
            documentRevision = current.documentRevision + 1,
            lastExportedDocumentRevision = current.lastExportedDocumentRevision,
            createdAt = current.createdAt,
            updatedAt = clock.now(),
        )
        write(restored, SnapshotPolicy.NONE)
        return restored
    }

    fun moveTo(projectId: String, destination: File): File {
        val source = projectDirectory(projectId)
        require(source.isDirectory) { "Project $projectId does not exist" }
        destination.parentFile?.mkdirs()
        if (!source.renameTo(destination)) throw IllegalStateException("Could not move project $projectId")
        return destination
    }

    private fun shouldSnapshot(project: ProjectState): Boolean {
        val latest = newestSnapshots(project.id).firstOrNull() ?: return true
        val summary = runCatching { codec.readSummary(latest) }.getOrNull() ?: return true
        if (project.documentRevision <= summary.documentRevision) return false
        return project.documentRevision - summary.documentRevision >= SNAPSHOT_DOCUMENT_REVISIONS ||
            Duration.between(summary.updatedAt, project.updatedAt) >= SNAPSHOT_INTERVAL
    }

    private fun rotateSnapshots(projectId: String) {
        val snapshots = newestSnapshots(projectId).toMutableList()
        while (snapshots.size > MAX_SNAPSHOTS) snapshots.removeAt(snapshots.lastIndex).delete()
        var bytes = snapshots.sumOf(File::length)
        while (snapshots.size > MIN_SNAPSHOTS && bytes > MAX_SNAPSHOT_BYTES) {
            val removed = snapshots.removeAt(snapshots.lastIndex)
            bytes -= removed.length()
            removed.delete()
        }
    }

    private fun newestSnapshots(projectId: String): List<File> = snapshotsDirectory(projectId).listFiles()
        ?.filter { it.isFile && it.extension == "velogpx" }
        ?.sortedByDescending { it.name }
        .orEmpty()

    private fun quarantine(file: File) {
        if (!file.isFile) return
        val directory = File(file.parentFile, "quarantine").apply { mkdirs() }
        val name = "current-${clock.now().toEpochMilli()}.corrupt.velogpx"
        runCatching { atomicFiles.copy(file, File(directory, name)) }
    }

    private fun projectDirectory(projectId: String): File {
        validateProjectId(projectId)
        return File(projectsDirectory, projectId).apply { mkdirs() }
    }

    private fun snapshotsDirectory(projectId: String) = File(projectDirectory(projectId), "snapshots")

    private fun validateProjectId(projectId: String) {
        require(runCatching { UUID.fromString(projectId) }.isSuccess) { "Invalid project ID" }
    }

    companion object {
        private const val CURRENT_NAME = "current.velogpx"
        private const val MAX_SNAPSHOTS = 8
        private const val MIN_SNAPSHOTS = 2
        private const val MAX_SNAPSHOT_BYTES = 256L * 1024L * 1024L
        private const val SNAPSHOT_DOCUMENT_REVISIONS = 20
        private val SNAPSHOT_INTERVAL = Duration.ofMinutes(5)
    }
}
