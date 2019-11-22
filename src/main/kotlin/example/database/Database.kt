package example.database

import com.github.seratch.jslack.lightning.util.JsonOps
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.stream.Collectors.joining

object Database {

    private val separator = File.separator.toString()
    private val baseDir = System.getProperty("user.home") + separator + ".new-relic-slack-app-home"

    fun save(entity: AppHomeSettings) {
        val path = Paths.get(filepath(entity.slackUserId))
        if (!path.parent.toFile().exists()) {
            Files.createDirectories(path.parent)
        }
        Files.write(path, JsonOps.toJsonString(entity).toByteArray())
    }

    fun find(slackUserId: String): AppHomeSettings? {
        val path = Paths.get(filepath(slackUserId))
        if (!path.toFile().exists()) {
            return null
        }
        val json = Files.readAllLines(Paths.get(filepath(slackUserId)))
                .stream()
                .collect(joining())
        return JsonOps.fromJson(json, AppHomeSettings::class.java)
    }

    fun delete(slackUserId: String) {
        Files.deleteIfExists(Paths.get(filepath(slackUserId)))
    }

    private fun filepath(slackUserId: String) = baseDir + separator + slackUserId

}

data class AppHomeSettings(
        val slackUserId: String,
        var accountId: String?,
        var defaultApplicationId: Int?,
        var restApiKey: String?,
        var queryApiKey: String?
)
