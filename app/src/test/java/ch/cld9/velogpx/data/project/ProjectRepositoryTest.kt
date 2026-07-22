package ch.cld9.velogpx.data.project

import ch.cld9.velogpx.io.GpxWriter
import ch.cld9.velogpx.model.GpxDocument
import ch.cld9.velogpx.model.GpxMetadata
import ch.cld9.velogpx.model.GpxPoint
import ch.cld9.velogpx.model.GpxTrack
import ch.cld9.velogpx.model.GpxTrackSegment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class ProjectRepositoryTest {
    @Rule @JvmField val temporary = TemporaryFolder()

    @Test fun catalogSupportsCreateOpenRenameDuplicateAndTrash() = runBlocking {
        val root = temporary.newFolder("repository")
        val clock = MutableClock()
        val repository = ProjectRepository.forTesting(root, clock = clock, dispatcher = Dispatchers.Unconfined)
        val first = repository.create("First", demoDocument("First"))
        clock.advance()
        val second = repository.create("Second", demoDocument("Second"))
        assertEquals(listOf("Second", "First"), repository.listProjects().map { it.title })

        val renamed = repository.rename(first.id, "Renamed")
        assertEquals("Renamed", repository.open(first.id).project.title)
        assertTrue(renamed.revision > first.revision)

        val duplicate = repository.duplicate(first.id)
        assertEquals("Renamed copy", duplicate.title)
        assertTrue(repository.listProjects().any { it.id == duplicate.id })

        repository.open(second.id)
        assertEquals(second.id, repository.openLastOrCreate().project.id)
        repository.moveToTrash(second.id)
        assertFalse(repository.listProjects().any { it.id == second.id })
    }

    @Test fun legacyAutosaveMigratesOnlyAfterNewArchiveIsDurable() = runBlocking {
        val root = temporary.newFolder("legacy-repository")
        val legacy = File(root, "source/projects/autosave.gpx").apply { parentFile!!.mkdirs() }
        legacy.outputStream().use { GpxWriter().write(demoDocument("Legacy tour"), it) }
        val repository = ProjectRepository.forTesting(root, legacy, dispatcher = Dispatchers.Unconfined)

        val opened = repository.openLastOrCreate()

        assertEquals(ProjectRecoverySource.LEGACY_AUTOSAVE, opened.source)
        assertEquals("Legacy tour", opened.project.title)
        assertFalse(legacy.exists())
        assertTrue(File(root, "legacy").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun corruptCurrentArchiveRecoversNewestSnapshot() = runBlocking {
        val root = temporary.newFolder("recovery-repository")
        val clock = MutableClock()
        val repository = ProjectRepository.forTesting(root, clock = clock, dispatcher = Dispatchers.Unconfined)
        val first = repository.create("Recovery", demoDocument("Recovery"))
        val second = first.nextRevision(
            document = first.document.copy(metadata = GpxMetadata(name = "Saved revision")),
            now = clock.advance(),
        )
        repository.save(second, SnapshotPolicy.FORCE)
        File(root, "projects/${first.id}/current.velogpx").writeText("not a zip")

        val recovered = repository.open(first.id)

        assertEquals(ProjectRecoverySource.SNAPSHOT, recovered.source)
        assertEquals(second.revision, recovered.project.revision)
        assertTrue(File(root, "projects/${first.id}/quarantine").listFiles().orEmpty().isNotEmpty())
    }

    @Test fun autosaveFlushPersistsLatestSubmittedState() = runBlocking {
        val root = temporary.newFolder("autosave-repository")
        val repository = ProjectRepository.forTesting(root, dispatcher = Dispatchers.Unconfined)
        val initial = repository.create("Autosave", demoDocument("Before"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = repository.autosaveSession(initial, scope, debounceMillis = 50, maximumDelayMillis = 100)
        val latest = initial.nextRevision(document = demoDocument("After"))
        session.submit(latest)

        session.flush()

        assertEquals("After", repository.open(initial.id).project.document.metadata?.name)
        session.close()
        scope.cancel()
    }

    @Test fun atomicWriterKeepsOldFileWhenReplacementFails() {
        val root = temporary.newFolder("atomic")
        val file = File(root, "state")
        val atomic = JvmAtomicFileAccess()
        atomic.write(file) { it.write("old".toByteArray()) }

        runCatching {
            atomic.write(file) {
                it.write("partial-new".toByteArray())
                error("simulated failure")
            }
        }

        assertEquals("old", file.readText())
    }

    private fun demoDocument(name: String) = GpxDocument(
        metadata = GpxMetadata(name = name),
        tracks = listOf(GpxTrack(name = name, segments = listOf(GpxTrackSegment(listOf(GpxPoint(47.0, 8.0), GpxPoint(47.1, 8.1)))))),
    )

    private class MutableClock : ProjectClock {
        private var value = Instant.parse("2026-07-22T00:00:00Z")
        override fun now(): Instant = value
        fun advance(): Instant { value = value.plusSeconds(60); return value }
    }
}
