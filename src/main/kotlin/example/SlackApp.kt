package example

import com.github.seratch.jslack.api.methods.MethodsClient
import com.github.seratch.jslack.api.methods.response.views.ViewsOpenResponse
import com.github.seratch.jslack.api.methods.response.views.ViewsPublishResponse
import com.github.seratch.jslack.api.methods.response.views.ViewsUpdateResponse
import com.github.seratch.jslack.api.model.block.Blocks.*
import com.github.seratch.jslack.api.model.block.LayoutBlock
import com.github.seratch.jslack.api.model.block.composition.BlockCompositions.*
import com.github.seratch.jslack.api.model.block.element.BlockElement
import com.github.seratch.jslack.api.model.block.element.BlockElements.*
import com.github.seratch.jslack.api.model.event.AppHomeOpenedEvent
import com.github.seratch.jslack.api.model.view.View
import com.github.seratch.jslack.api.model.view.Views.*
import com.github.seratch.jslack.lightning.App
import com.github.seratch.jslack.lightning.AppConfig
import com.github.seratch.jslack.lightning.util.JsonOps
import example.database.Database
import example.database.FileDatabase
import example.database.Settings
import example.new_relic.insights.NewRelicInsightsApi
import example.new_relic.insights.QueryResponse
import example.new_relic.rest.NewRelicRestApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URLEncoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.*

@Configuration
class SlackApp {

    companion object {
        private val zoneId = ZoneId.systemDefault()
        private val logger = LoggerFactory.getLogger(SlackApp::class.java)
    }

    object CallbackIds {
        const val settingsModal = "settings-modal"
        const val queryModal = "query-modal"
        const val queryHistoryModal = "query-history-modal"
    }

    object BlockIds {
        const val inputAccountId = "input-account-id"
        const val inputRestApiKey = "input-rest-api-key"
        const val inputQueryApiKey = "input-query-api-key"
        const val inputQuery = "input-query"
    }

    object ActionIds {
        const val input = "input"
        const val settingsButton = "settings-button"
        const val clearSettingsButton = "clear-settings-button"
        const val queryButton = "query-button"
        const val queryHistoryButton = "query-history-button"
        const val queryRadioButton = "query-radio-button"
        const val selectAppOverlayMenu = "select-app-overlay-menu"
        const val viewInBrowserButton = "view-in-browser-button"
    }

    @Bean
    fun loadAppConfig(): AppConfig = FileConfigLoader.loadAppConfig()

    @Bean
    fun database(): Database = FileDatabase()

    @Bean
    fun initApp(appConfig: AppConfig, database: Database): App {

        val app = App(appConfig)

        app.use { req, _, chain ->
            if (logger.isDebugEnabled) {
                logger.debug("Dumping request body for debugging...\n\n${req.requestBodyAsString}\n")
            }
            chain.next(req)
        }

        app.blockAction(ActionIds.viewInBrowserButton) { _, ctx ->
            ctx.ack()
        }

        // --------------------------
        // App Home
        // --------------------------

        app.event(AppHomeOpenedEvent::class.java) { payload, ctx ->
            val slackUserId = payload.event.user
            val settings = database.findSettings(slackUserId)
            val newRelicApi = newRelicRestApi(settings?.restApiKey)
            updateAppHome(slackUserId, settings, ctx.client(), newRelicApi)
            ctx.ack()
        }

        app.blockAction(ActionIds.selectAppOverlayMenu) { req, ctx ->
            val slackUserId = req.payload.user.id
            val appId = req.payload.actions[0].selectedOption.value.toInt()
            val row = database.findSettings(slackUserId)
            if (row != null) {
                row.defaultApplicationId = appId
                database.saveSettings(row)
                val newRelicApi = newRelicRestApi(row.restApiKey)
                updateAppHome(slackUserId, row, ctx.client(), newRelicApi)
            }
            ctx.ack()
        }

        // --------------------------
        // New Relic Settings
        // --------------------------

        app.blockAction(ActionIds.settingsButton) { req, ctx ->
            val slackUserId = req.payload.user.id
            val triggerId = req.payload.triggerId
            val settings = database.findSettings(slackUserId)
            val view = buildSettingsModalView(settings)
            viewsOpen(triggerId, view, ctx.client())
            ctx.ack()
        }

        app.viewSubmission(CallbackIds.settingsModal) { req, ctx ->
            val slackUserId = req.payload.user.id
            val state = req.payload.view.state.values
            val accountId = state?.get(BlockIds.inputAccountId)?.get(ActionIds.input)?.value
            val restApiKey = state?.get(BlockIds.inputRestApiKey)?.get(ActionIds.input)?.value
            val queryApiKey = state?.get(BlockIds.inputQueryApiKey)?.get(ActionIds.input)?.value

            // server-side validation
            val errors = mutableMapOf<String, String>()
            if (accountId == null || !accountId.matches("\\d+".toRegex())) {
                errors[BlockIds.inputAccountId] = "Account Id must be a numeric value"
            }
            if (restApiKey == null || !restApiKey.matches("NRRA-\\w{42}".toRegex())) {
                errors[BlockIds.inputRestApiKey] = "REST API Key must be in a valid format"
            }
            if (queryApiKey == null || !queryApiKey.matches("NRIQ-\\w{32}".toRegex())) {
                errors[BlockIds.inputQueryApiKey] = "Query API Key must be in a valid format"
            }
            if (errors.isNotEmpty()) {
                ctx.ack { it.responseAction("errors").errors(errors) } // display errors in the modal
            } else {
                saveAndViewsPublish(
                        slackUserId,
                        accountId!!,
                        restApiKey!!,
                        queryApiKey!!,
                        database,
                        ctx.client()
                )
                ctx.ack() // close
            }
        }

        app.blockAction(ActionIds.clearSettingsButton) { req, ctx ->
            val slackUserId = req.payload.user.id
            database.deleteAll(slackUserId)
            updateAppHome(slackUserId, null, ctx.client(), null)
            ctx.ack()
        }

        // --------------------------
        // NRQL Query Runner
        // --------------------------

        app.blockAction(ActionIds.queryButton) { req, ctx ->
            val slackUserId = req.payload.user.id
            val triggerId = req.payload.triggerId
            val settings = database.findSettings(slackUserId)
            val newRelic = newRelicRestApi(settings?.restApiKey)
            val accountId = settings?.accountId
            val applicationId = settings?.defaultApplicationId
                    ?: newRelic?.applicationsList()?.applications?.first()?.id
            val query = buildQuery(null, applicationId)
            val insightsApi = newRelicInsightsApi(settings)!!
            val view = buildQueryModalView(
                    accountId,
                    applicationId,
                    query,
                    insightsApi.runQuery(query)
            )
            database.saveQuery(slackUserId, query)

            viewsOpen(triggerId, view, ctx.client())
            ctx.ack()
        }

        app.viewSubmission(CallbackIds.queryModal) { req, ctx ->
            val slackUserId = req.payload.user.id
            val state = req.payload.view.state.values
            val settings = database.findSettings(slackUserId)
            val query = buildQuery(
                    state?.get(BlockIds.inputQuery)?.get(ActionIds.input)?.value,
                    settings?.defaultApplicationId
            )
            val insightsApi = newRelicInsightsApi(settings)!!
            val view = buildQueryModalView(
                    settings?.accountId,
                    settings?.defaultApplicationId,
                    query,
                    insightsApi.runQuery(query)
            )
            database.saveQuery(slackUserId, query)

            if (logger.isDebugEnabled) {
                logger.debug("Updating a view by responding to a view_submission request\n\n${JsonOps.toJsonString(view)}\n")
            }
            // Will update the current modal
            ctx.ack { it.responseAction("update").view(view) }
        }

        app.blockAction(ActionIds.queryHistoryButton) { req, ctx ->
            val slackUserId = req.payload.user.id
            val queries = database.findQueries(slackUserId)
            val view = buildQueryHistoryModalView(queries)
            viewsUpdate(req.payload.view.id, view, ctx.client())
            ctx.ack()
        }

        app.blockAction(ActionIds.queryRadioButton) { req, ctx ->
            val slackUserId = req.payload.user.id
            val queries = database.findQueries(slackUserId)
            val idx = req.payload.actions[0]?.selectedOption?.value?.toInt()
            val query = if (idx != null) queries[idx] else null
            val settings = database.findSettings(slackUserId)
            val insightsApi = newRelicInsightsApi(settings)!!
            val view = buildQueryModalView(
                    settings?.accountId,
                    settings?.defaultApplicationId,
                    query,
                    insightsApi.runQuery(query)
            )
            database.saveQuery(slackUserId, query)

            viewsUpdate(req.payload.view.id, view, ctx.client())
            ctx.ack()
        }

        return app
    }

    // ----------------------------------------------------
    // Internal Methods
    // ----------------------------------------------------

    // --------------
    // API Calls

    private fun newRelicRestApi(restApiKey: String?): NewRelicRestApi? =
            if (restApiKey != null) NewRelicRestApi(restApiKey)
            else null


    private fun newRelicInsightsApi(settings: Settings?): NewRelicInsightsApi? =
            if (settings?.queryApiKey != null) {
                NewRelicInsightsApi(settings.accountId!!, settings.queryApiKey!!)
            } else null

    private fun viewsOpen(triggerId: String, view: View?, client: MethodsClient): ViewsOpenResponse? {
        if (logger.isDebugEnabled) {
            logger.debug("Going to send this view to views.open API\n\n${JsonOps.toJsonString(view)}\n")
        }
        return client.viewsOpen { it.triggerId(triggerId).view(view) }
    }

    private fun viewsUpdate(viewId: String, view: View, client: MethodsClient): ViewsUpdateResponse? {
        if (logger.isDebugEnabled) {
            logger.debug("Going to send this view to views.update API\n\n${JsonOps.toJsonString(view)}\n")
        }
        return client.viewsUpdate { it.viewId(viewId).view(view) }
    }

    private fun viewsPublish(blocks: List<LayoutBlock>, client: MethodsClient, slackUserId: String): ViewsPublishResponse? {
        val view = view { it.type("home").blocks(blocks) }
        if (logger.isDebugEnabled) {
            logger.debug("Going to send this view to views.publish API\n\n${JsonOps.toJsonString(view)}\n")
        }
        return client.viewsPublish { it.userId(slackUserId).view(view) }
    }

    // --------------
    // Building App Home

    private fun saveAndViewsPublish(
            slackUserId: String,
            accountId: String,
            restApiKey: String,
            queryApiKey: String,
            database: Database,
            client: MethodsClient) {
        GlobalScope.async {
            val row = database.findSettings(slackUserId)
            if (row != null) {
                row.accountId = accountId
                row.restApiKey = restApiKey
                row.queryApiKey = queryApiKey
                database.saveSettings(row)
                val newRelicApi = newRelicRestApi(row.restApiKey)
                updateAppHome(slackUserId, row, client, newRelicApi)
            } else {
                val newRelicApi = newRelicRestApi(restApiKey)
                val newRow = Settings(
                        slackUserId = slackUserId,
                        accountId = accountId,
                        restApiKey = restApiKey,
                        queryApiKey = queryApiKey,
                        defaultApplicationId = newRelicApi?.applicationsList()?.applications?.first()?.id
                )
                database.saveSettings(newRow)
                updateAppHome(slackUserId, newRow, client, newRelicApi)
            }
        }
    }

    private val emptyListOfBlock = emptyList<LayoutBlock>()

    private fun updateAppHome(
            slackUserId: String,
            settings: Settings?,
            client: MethodsClient,
            newRelicRestApi: NewRelicRestApi?) {

        if (newRelicRestApi == null) {
            // need initialize
            viewsPublish(asBlocks(
                    section { it.text(markdownText("*:loud_sound: Unlock your personalized :new-relic: dashboard!*")) },
                    actions {
                        it.elements(asElements(button { b ->
                            b.actionId(ActionIds.settingsButton).style("primary").text(plainText("Enable Now"))
                        }))
                    }), client, slackUserId)
            return
        }

        val headerBlock = section {
            it.text(markdownText("*:new-relic: New Relic Dashboard :new-relic:*"))
                    .accessory(button { b ->
                        b.actionId(ActionIds.clearSettingsButton).style("danger")
                                .text(plainText("Clear Settings"))
                                .confirm(confirmationDialog { cd ->
                                    cd.title(plainText("Clear Settings")).text(plainText("Are you sure?"))
                                })
                    })
        }

        val queryBlock = actions(asElements(button {
            it.actionId(ActionIds.queryButton).text(plainText(":pencil: Query Runner"))
        }))

        val applications = newRelicRestApi.applicationsList()!!.applications
        val appSelectorBlock = section {
            it.text(plainText("Select Application :arrow_right:"))
                    .accessory(overflowMenu { ofm ->
                        ofm.actionId(ActionIds.selectAppOverlayMenu).options(applications.map { app ->
                            option { o -> o.text(plainText(app.name)).value(app.id.toString()) }
                        })
                    })
        }

        if (applications.isEmpty()) {
            viewsPublish(listOf(headerBlock, appSelectorBlock), client, slackUserId)
            return
        }

        val dividerBlock = divider()

        val firstApp = applications.first()
        val currentApp =
                if (settings != null) {
                    val filteredApps = applications.filter { it.id == settings.defaultApplicationId }
                    if (filteredApps.isEmpty()) firstApp else filteredApps.first()
                } else firstApp

        val appHeaderBlock = section { it.text(markdownText("*:mag: Application*")) }
        val appHealthStatus = if (currentApp.healthStatus == "red") ":red_circle:" else ":large_blue_circle:"
        val date =
                if (currentApp.lastReportedAt != null) ZonedDateTime.parse(currentApp.lastReportedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).withZoneSameInstant(zoneId)
                else null
        val appBlock = section {
            it.text(markdownText("""
                        Name: *${currentApp.name}*
                        Language: *:${currentApp.language}:*
                        Health Status: *${appHealthStatus}*
                        Last Reported: *${date?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) ?: "-"}*
                        """.trimIndent())
            ).accessory(button { b ->
                b.actionId(ActionIds.viewInBrowserButton)
                        .text(plainText("View in browser"))
                        .url("https://rpm.newrelic.com/accounts/${settings!!.accountId}/applications/${currentApp.id}")
            })
        }

        val hostsHeaderBlock = section { it.text(markdownText("*:electric_plug: Hosts*")) }

        val hosts = newRelicRestApi.applicationHostsList(currentApp.id)!!.applicationHosts
        val hostsBlocks =
                if (hosts.isNotEmpty()) {
                    val hostsBlock = section {
                        it.fields(hosts.take(10).map { host ->
                            val hostHealthStatus = if (host.healthStatus == "red") ":red_circle:" else ":large_blue_circle:"
                            markdownText("""
                                Host: *${host.host}*
                                Health Status: *${hostHealthStatus}*
                            """.trimIndent())
                        })
                    }
                    listOf(hostsHeaderBlock, dividerBlock, hostsBlock)
                } else emptyListOfBlock

        val violationsHeaderBlock = section {
            it.text(markdownText("*:warning: Alert Violations*"))
                    .accessory(button { b ->
                        b.actionId(ActionIds.viewInBrowserButton)
                                .text(plainText("View in browser"))
                                .url("https://rpm.newrelic.com/accounts/${settings!!.accountId}/applications/${currentApp.id}/violations")
                    })
        }
        val violations = newRelicRestApi.alertsViolationsList(currentApp.id)!!.violations
        val violationsBlock = section {
            it.fields(violations.take(10).map { v ->
                val opened = ZonedDateTime.ofInstant(Date(v.openedAt).toInstant(), zoneId)
                markdownText("""
                    Priority: *${v.priority}*
                    Violation: *${v.label}*
                    Opened: *${opened.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}*
                """.trimIndent())
            })
        }

        val violationsBlocks =
                if (violations.isNotEmpty()) asBlocks(violationsHeaderBlock, dividerBlock, violationsBlock)
                else emptyListOfBlock

        val blocks =
                asBlocks(headerBlock, queryBlock, dividerBlock, appSelectorBlock, appHeaderBlock, dividerBlock, appBlock) +
                        hostsBlocks + violationsBlocks
        viewsPublish(blocks, client, slackUserId)
    }

    // --------------
    // Building Modals

    private fun buildSettingsModalView(settings: Settings?): View? {
        return view {
            it.type("modal")
                    .callbackId(CallbackIds.settingsModal)
                    .submit(viewSubmit { vs -> vs.type("plain_text").text("Save") })
                    .close(viewClose { vc -> vc.type("plain_text").text("Close") })
                    .title(viewTitle { vt -> vt.type("plain_text").text("New Relic Settings") })
                    .blocks(asBlocks(
                            input { i ->
                                i.blockId(BlockIds.inputAccountId)
                                        .label(plainText("Account Id"))
                                        .element(plainTextInput { pti ->
                                            pti.actionId(ActionIds.input)
                                                    .placeholder(plainText("Check rpm.newrelic.com/accounts/"))
                                                    .initialValue(settings?.accountId)
                                        }).optional(false)
                            },
                            input { i ->
                                i.blockId(BlockIds.inputRestApiKey)
                                        .label(plainText("REST API Key"))
                                        .element(plainTextInput { pti ->
                                            pti.actionId(ActionIds.input)
                                                    .placeholder(plainText("Check rpm.newrelic.com/accounts/{id}/integrations?page=api_keys"))
                                                    .initialValue(settings?.restApiKey ?: "NRRA-")
                                        }).optional(false)
                            },
                            input { i ->
                                i.blockId(BlockIds.inputQueryApiKey)
                                        .label(plainText("Insights Query API Key"))
                                        .element(plainTextInput { pti ->
                                            pti.actionId(ActionIds.input)
                                                    .placeholder(plainText("Check insights.newrelic.com/accounts/{id}}/manage/api_keys"))
                                                    .initialValue(settings?.queryApiKey ?: "NRIQ-")
                                        }).optional(false)
                            }
                    ))
        }
    }

    private fun buildQuery(query: String?, applicationId: Int?): String? {
        val defaultQuery = if (applicationId != null) {
            "SELECT name, host, duration, timestamp FROM Transaction SINCE 30 MINUTES AGO WHERE appId = $applicationId"
        } else {
            "SELECT name, host, duration, timestamp FROM Transaction SINCE 30 MINUTES AGO"
        }
        return query ?: defaultQuery
    }

    private fun buildQueryModalView(
            accountId: String?,
            applicationId: Int?,
            query: String? = null,
            queryResponse: QueryResponse? = null): View {
        val queryToRun = buildQuery(query, applicationId)
        return view {
            it.type("modal")
                    .callbackId(CallbackIds.queryModal)
                    .submit(viewSubmit { vs -> vs.type("plain_text").text("Run") })
                    .close(viewClose { vc -> vc.type("plain_text").text("Close") })
                    .title(viewTitle { vt -> vt.type("plain_text").text("Insights Query Runner") })
                    .blocks(asBlocks(
                            actions { bs ->
                                bs.elements(asElements(
                                        button { b ->
                                            b.actionId(ActionIds.viewInBrowserButton)
                                                    .text(plainText("What's NRQL?"))
                                                    .url("https://docs.newrelic.com/docs/query-data/nrql-new-relic-query-language/getting-started/nrql-syntax-components-functions")
                                        },
                                        button { b ->
                                            b.actionId(ActionIds.queryHistoryButton).text(plainText("Query History"))
                                        }
                                ))
                            },
                            input { i ->
                                i.blockId(BlockIds.inputQuery)
                                        .label(plainText("Query (NRQL)"))
                                        .element(plainTextInput { pti ->
                                            pti.actionId(ActionIds.input)
                                                    .placeholder(plainText("Write an NRQL query here"))
                                                    .initialValue(queryToRun)
                                                    .multiline(true)
                                        }).optional(false)
                            }
                    ) + buildQueryResultBlocks(accountId, queryToRun, queryResponse))
        }
    }

    private fun buildQueryResultBlocks(accountId: String?, query: String?, queryResponse: QueryResponse?): List<LayoutBlock> {
        val blocks = mutableListOf<LayoutBlock>()
        if (queryResponse?.error != null) {
            blocks.add(divider())
            blocks.add(section { it.text(markdownText(queryResponse.error)) })
        } else if (queryResponse?.results != null) {
            for (result in queryResponse.results) {
                if (result["events"] != null) {
                    if ((result["events"] ?: error("")).isNotEmpty()) {
                        val events = (result["events"] ?: error("")).take(20)
                        for (event in events) {
                            blocks.add(divider())
                            val block = section {
                                it.text(markdownText(event.keys.joinToString("\n") { key -> "$key: *${event[key]}*" }))
                            }
                            blocks.add(block)
                        }
                    } else {
                        blocks.add(divider())
                        val block = section { it.text(markdownText("No data found.")) }
                        blocks.add(block)
                    }
                } else if (result.size == 1) {
                    val res = result.values.first()[0]
                    blocks.add(divider())
                    val block = section {
                        it.text(markdownText(res.keys.joinToString("\n") { key -> "$key: *${res[key]}*" }))
                    }
                    blocks.add(block)
                }
            }
        }
        if (accountId != null) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            blocks.add(divider())
            blocks.add(actions {
                it.elements(asElements(button { b ->
                    b.actionId(ActionIds.viewInBrowserButton)
                            .text(plainText("View in browser"))
                            .url("https://insights.newrelic.com/accounts/$accountId/query?query=$encodedQuery")
                }))
            })
        }
        return blocks
    }

    private fun buildQueryHistoryModalView(queries: List<String>): View {
        val options = queries.mapIndexed { idx, q ->
            option {
                it.text(plainText(q.take(67) + (if (q.length > 67) "..." else ""))).value(idx.toString())
            }
        }
        val accessory: BlockElement? = if (options.isEmpty()) null else {
            radioButtons { it.actionId(ActionIds.queryRadioButton).options(options) }
        }
        return view {
            it.type("modal")
                    .callbackId(CallbackIds.queryHistoryModal)
                    .close(viewClose { vc -> vc.type("plain_text").text("Close") })
                    .title(viewTitle { vt -> vt.type("plain_text").text("Insights Query History") })
                    .blocks(listOf(section { s ->
                        s.text(markdownText { m ->
                            m.text("Here is the list of the queries you recently ran. Select a query you'd like to run again.")
                        }).accessory(accessory)
                    }))
        }
    }

}