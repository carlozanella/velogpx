package ch.cld9.velogpx.data.project

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class ProjectCatalogStore(
    private val file: File,
    private val atomicFiles: AtomicFileAccess,
) {
    fun read(): ProjectCatalog {
        if (!file.exists()) return ProjectCatalog()
        atomicFiles.prepareRead(file)
        val root = JSONObject(file.readText(Charsets.UTF_8))
        val version = root.optInt("schemaVersion", -1)
        require(version in 1..PROJECT_SCHEMA_VERSION) { "Unsupported project catalog version $version" }
        val projects = root.optJSONArray("projects") ?: JSONArray()
        return ProjectCatalog(
            schemaVersion = version,
            lastProjectId = root.nullableString("lastProjectId"),
            projects = List(projects.length()) { index -> decodeSummary(projects.getJSONObject(index)) },
        )
    }

    fun write(catalog: ProjectCatalog) {
        val root = JSONObject().apply {
            put("schemaVersion", PROJECT_SCHEMA_VERSION)
            put("lastProjectId", catalog.lastProjectId ?: JSONObject.NULL)
            put("projects", JSONArray(catalog.projects.map(::encodeSummary)))
        }
        atomicFiles.write(file) { it.write(root.toString().toByteArray(Charsets.UTF_8)) }
    }

    private fun encodeSummary(value: ProjectSummary) = JSONObject().apply {
        put("id", value.id); put("title", value.title); put("revision", value.revision)
        put("documentRevision", value.documentRevision); put("createdAt", value.createdAt.toString())
        put("updatedAt", value.updatedAt.toString()); put("lastOpenedAt", value.lastOpenedAt.toString())
        put("trackCount", value.trackCount); put("routeCount", value.routeCount)
        put("waypointCount", value.waypointCount); put("pointCount", value.pointCount)
    }

    private fun decodeSummary(value: JSONObject) = ProjectSummary(
        id = value.getString("id"), title = value.getString("title"), revision = value.getLong("revision"),
        documentRevision = value.getLong("documentRevision"), createdAt = Instant.parse(value.getString("createdAt")),
        updatedAt = Instant.parse(value.getString("updatedAt")), lastOpenedAt = Instant.parse(value.getString("lastOpenedAt")),
        trackCount = value.optInt("trackCount"), routeCount = value.optInt("routeCount"),
        waypointCount = value.optInt("waypointCount"), pointCount = value.optInt("pointCount"),
    )
}

private fun JSONObject.nullableString(name: String): String? = if (!has(name) || isNull(name)) null else getString(name)
