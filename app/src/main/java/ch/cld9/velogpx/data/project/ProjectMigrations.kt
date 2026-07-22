package ch.cld9.velogpx.data.project

import org.json.JSONObject

interface ProjectMigration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(source: JSONObject): JSONObject
}

internal object ProjectMigrations {
    private val migrations: List<ProjectMigration> = emptyList()

    fun migrate(source: JSONObject): JSONObject {
        var current = source
        var version = current.getInt("schemaVersion")
        while (version < PROJECT_SCHEMA_VERSION) {
            val migration = migrations.singleOrNull { it.fromVersion == version }
                ?: throw ProjectFormatException("No migration exists for project schema $version")
            require(migration.toVersion > migration.fromVersion) { "Project migration must advance the schema" }
            current = migration.migrate(JSONObject(current.toString()))
            version = migration.toVersion
            current.put("schemaVersion", version)
        }
        return current
    }
}
