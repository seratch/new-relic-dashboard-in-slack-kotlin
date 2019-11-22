package example

import com.github.seratch.jslack.lightning.AppConfig
import com.google.gson.Gson
import com.google.gson.JsonElement
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.stream.Collectors

object FileConfigLoader {
    private val logger = LoggerFactory.getLogger(FileConfigLoader::class.java)
    private val classLoader = FileConfigLoader::class.java.classLoader

    fun loadAppConfig(): AppConfig {
        val config = AppConfig()
        try {
            // src/main/resources/appConfig.json
            classLoader.getResourceAsStream("appConfig.json").use { ins ->
                InputStreamReader(ins).use { isr ->
                    val json: String = BufferedReader(isr).lines().collect(Collectors.joining())
                    val j = Gson().fromJson(json, JsonElement::class.java).asJsonObject
                    config.signingSecret = j["signingSecret"].asString
                    config.singleTeamBotToken = j["singleTeamBotToken"].asString
                }
            }
        } catch (e: IOException) {
            logger.error(e.message, e)
        }
        return config
    }
}