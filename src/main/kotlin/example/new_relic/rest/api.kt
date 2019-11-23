package example.new_relic.rest

import com.github.seratch.jslack.lightning.util.JsonOps
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class NewRelicRestApi(private val apiKey: String) {

    private val logger = LoggerFactory.getLogger(NewRelicRestApi::class.java)
    private val httpClient = HttpClient.newHttpClient()

    fun applicationsList(): ApplicationsListResponse? {
        val uri = URI("https://api.newrelic.com/v2/applications.json")
        val httpRequest = buildRequest(uri)
        val response = send(httpRequest)
        if (logger.isDebugEnabled) {
            logger.debug("New Relic /v2/applications.json\n\n${response.body()}\n")
        }
        return JsonOps.fromJson(response.body(), ApplicationsListResponse::class.java)
    }

    fun applicationHostsList(applicationId: Int): ApplicationHostsListResponse? {
        val uri = URI("https://api.newrelic.com/v2/applications/${applicationId}/hosts.json")
        val httpRequest = buildRequest(uri)
        val response = send(httpRequest)
        if (logger.isDebugEnabled) {
            logger.debug("New Relic /v2/applications/${applicationId}/hosts.json\n\n${response.body()}\n")
        }
        return JsonOps.fromJson(response.body(), ApplicationHostsListResponse::class.java)
    }

    fun alertsViolationsList(applicationId: Int): AlertsViolationsListResponse? {
        val uri = URI("https://api.newrelic.com/v2/alerts_violations.json")
        val httpRequest = buildRequest(uri)
        val response = send(httpRequest)
        if (logger.isDebugEnabled) {
            logger.debug("New Relic /v2/alerts_violations.json\n\n${response.body()}\n")
        }
        val fullResponse = JsonOps.fromJson(response.body(), AlertsViolationsListResponse::class.java)
        val violations = fullResponse.violations.filter { it.entity.type == "Application" && it.entity.id == applicationId }
        return AlertsViolationsListResponse(violations)
    }

    private fun send(httpRequest: HttpRequest?): HttpResponse<String> {
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    }

    private fun buildRequest(uri: URI): HttpRequest? {
        return HttpRequest.newBuilder()
                .GET()
                .uri(uri)
                .header("X-Api-Key", apiKey)
                .build()
    }

}

// -------------------------------------------------------

// https://rpm.newrelic.com/api/explore/applications/list
data class ApplicationsListResponse(val applications: List<Application>)

// https://rpm.newrelic.com/api/explore/application_hosts/list
data class ApplicationHostsListResponse(val applicationHosts: List<ApplicationHost>)

// https://rpm.newrelic.com/api/explore/alerts_violations/list
data class AlertsViolationsListResponse(val violations: List<AlertsViolation>)

// -------------------------------------------------------

data class Application(
        val id: Int,
        val name: String,
        val language: String,
        val healthStatus: String,
        val reporting: Boolean,
        val lastReportedAt: String?,
        val settings: ApplicationSettings,
        val applicationSummary: ApplicationSummary?,
        val links: ApplicationLinks
)

data class ApplicationHost(
        val id: Int,
        val applicationName: String,
        val host: String,
        val healthStatus: String,
        val applicationSummary: ApplicationSummary?,
        val links: ApplicationHostLinks
)

data class ApplicationSettings(
        val appApdexThreshold: Float,
        val endUserApdexThreshold: Float,
        val enableRealUserMonitoring: Boolean,
        val useServerSideConfig: Boolean
)

data class ApplicationSummary(
        val responseTime: Float,
        val throughput: Float,
        val errorRate: Float,
        val apdexTarget: Float,
        val apdexScore: Float,
        val hostCount: Int,
        val instanceCount: Int,
        val concurrentInstanceCount: Int
)

data class ApplicationLinks(
        val applicationInstances: List<Int>,
        val servers: List<Int>,
        val applicationHosts: List<Int>
)

data class ApplicationHostLinks(
        val application: Int,
        val applicationInstances: List<Int>
)

data class AlertsViolation(
        val id: Int,
        val label: String,
        val duration: Int,
        val policyName: String,
        val conditionName: String,
        val priority: String,
        val openedAt: Long,
        val entity: Entity,
        val links: Links
) {
    data class Entity(
            val product: String,
            val type: String,
            val groupId: Int,
            val id: Int,
            val name: String
    )

    data class Links(
            val policyId: Int,
            val conditionId: Int,
            val incidentId: Int
    )
}