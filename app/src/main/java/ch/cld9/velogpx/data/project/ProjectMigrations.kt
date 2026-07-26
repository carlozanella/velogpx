package ch.cld9.velogpx.data.project

import org.json.JSONObject

interface ProjectMigration {
    val fromVersion: Int
    val toVersion: Int
    fun migrate(source: JSONObject): JSONObject
}

internal object ProjectMigrations {
    private val migrations: List<ProjectMigration> = listOf(V1ToV2TrackGroups)

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

private object V1ToV2TrackGroups : ProjectMigration {
    override val fromVersion = 1
    override val toVersion = 2

    override fun migrate(source: JSONObject): JSONObject {
        val editor = source.optJSONObject("editor") ?: JSONObject().also { source.put("editor", it) }
        val groups = editor.optJSONArray("groups") ?: org.json.JSONArray()
        for (index in 0 until groups.length()) {
            val group = groups.getJSONObject(index)
            if (!group.has("trackIds")) group.put("trackIds", group.optJSONArray("layerIds") ?: org.json.JSONArray())
            group.remove("layerIds")
            group.put("visible", group.optBoolean("visible", true))
        }
        editor.put("groups", groups)
        return source
    }
}
