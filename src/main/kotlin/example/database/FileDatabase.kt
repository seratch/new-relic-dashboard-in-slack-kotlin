package example.database

import com.github.seratch.jslack.lightning.util.JsonOps
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.stream.Collectors.joining


class FileDatabase : Database {

    private val separator = File.separator.toString()
    private val baseDir = System.getProperty("user.home") + separator + ".new-relic-slack-app-home"

    override fun saveSettings(entity: Settings) {
        val path = Paths.get(settingsFilepath(entity.slackUserId))
        if (!path.parent.toFile().exists()) {
            Files.createDirectories(path.parent)
        }
        Files.write(path, JsonOps.toJsonString(entity).toByteArray())
    }

    override fun findSettings(slackUserId: String): Settings? {
        val path = Paths.get(settingsFilepath(slackUserId))
        if (!path.toFile().exists()) {
            return null
        }
        val json = Files.readAllLines(path).stream().collect(joining())
        return JsonOps.fromJson(json, Settings::class.java)
    }

    override fun deleteAll(slackUserId: String) {
        Files.deleteIfExists(Paths.get(settingsFilepath(slackUserId)))
        Files.deleteIfExists(Paths.get(queriesFilepath(slackUserId)))
    }

    override fun saveQuery(slackUserId: String, query: String?) {
        if (query == null) return
        val queries = listOf(query) + findQueries(slackUserId)
        val uniqueQueries = queries.filterIndexed { idx, q -> queries.indexOf(q) == idx }

        val path = Paths.get(queriesFilepath(slackUserId))
        Files.write(path, JsonOps.toJsonString(uniqueQueries).toByteArray())
    }

    override fun findQueries(slackUserId: String): List<String> {
        val path = Paths.get(queriesFilepath(slackUserId))
        if (!path.parent.toFile().exists()) {
            Files.createDirectories(path.parent)
        }
        if (!path.toFile().exists()) {
            return emptyList()
        }
        val json = Files.readAllLines(path).stream().collect(joining())
        val queries = fromJsonToList(json, String::class.java)
        return queries ?: emptyList()
    }

    private fun settingsFilepath(slackUserId: String) = baseDir + separator + "settings" + separator + slackUserId + ".json"

    private fun queriesFilepath(slackUserId: String) = baseDir + separator + "queries" + separator + slackUserId + ".json"

    private fun <T> fromJsonToList(json: String?, _clazz: Class<T>?): List<T>? {
        return if (json == null) null else Gson().fromJson(json, object : TypeToken<T>() {}.type)
    }
}
