package example.new_relic.insights

import com.github.seratch.jslack.lightning.util.JsonOps
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class NewRelicInsightsApi(
        private val accountId: String,
        private val queryApiKey: String
) {

    private val logger = LoggerFactory.getLogger(NewRelicInsightsApi::class.java)
    private val httpClient = HttpClient.newHttpClient()

    fun runQuery(nrql: String?): QueryResponse? {
        val query = URLEncoder.encode(nrql, "UTF-8")
        val uri = URI("https://insights-api.newrelic.com/v1/accounts/$accountId/query?nrql=$query")
        val httpRequest = buildRequest(uri)
        val response = send(httpRequest)
        if (logger.isDebugEnabled) {
            logger.debug("New Relic /v1/accounts/$accountId/query?nrql=$query\n\n${response.body()}\n")
        }
        return try {
            JsonOps.fromJson(response.body(), QueryResponse::class.java)
        } catch (e: Exception) {
            val res = JsonOps.fromJson(response.body(), AggregationQueryResponse::class.java)
            val results = listOf(mapOf("aggregation" to listOf(res!!.results!![0])))
            QueryResponse(null, results, res.performanceStats, res.metadata)
        }
    }

    private fun send(httpRequest: HttpRequest?): HttpResponse<String> {
        return httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())
    }

    private fun buildRequest(uri: URI): HttpRequest? {
        return HttpRequest.newBuilder()
                .GET()
                .uri(uri)
                .header("X-Query-Key", queryApiKey)
                .build()
    }
}

data class QueryResponse(
        var error: String?,
        val results: List<Map<String, List<Map<String, String>>>>?,
        val performanceStats: PerformanceStats?,
        val metadata: Metadata?
)

data class AggregationQueryResponse(
        var error: String?,
        val results: List<Map<String, String>>?,
        val performanceStats: PerformanceStats?,
        val metadata: Metadata?
)

data class PerformanceStats(
        val inspectedCount: Int,
        val omittedCount: Int,
        val matchCount: Int,
        val wallClockTime: Int
)
data class Metadata(
        val eventTypes: List<String>,
        val eventType: String,
        val openEnded: Boolean,
        val beginTime: String,
        val endTime: String,
        val beginTimeMillis: Long,
        val endTimeMillis: Long,
        val rawSince: String,
        val rawUntil: String,
        val rawCompareWith: String,
        val guid: String,
        val routerGuid: String,
        val messages: List<String>,
        val contents: List<Content>
)

data class Content(
        val function: String,
        val attribute: String,
        val simple: Boolean
)
