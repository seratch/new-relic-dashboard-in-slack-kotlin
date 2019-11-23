package example

import com.github.seratch.jslack.api.methods.MethodsClient
import com.github.seratch.jslack.api.methods.response.views.ViewsOpenResponse
import com.github.seratch.jslack.api.methods.response.views.ViewsPublishResponse
import com.github.seratch.jslack.api.model.block.*
import com.github.seratch.jslack.api.model.block.composition.ConfirmationDialogObject
import com.github.seratch.jslack.api.model.block.composition.MarkdownTextObject
import com.github.seratch.jslack.api.model.block.composition.OptionObject
import com.github.seratch.jslack.api.model.block.composition.PlainTextObject
import com.github.seratch.jslack.api.model.block.element.ButtonElement
import com.github.seratch.jslack.api.model.block.element.OverflowMenuElement
import com.github.seratch.jslack.api.model.block.element.PlainTextInputElement
import com.github.seratch.jslack.api.model.event.AppHomeOpenedEvent
import com.github.seratch.jslack.api.model.view.View
import com.github.seratch.jslack.api.model.view.ViewClose
import com.github.seratch.jslack.api.model.view.ViewSubmit
import com.github.seratch.jslack.api.model.view.ViewTitle
import com.github.seratch.jslack.lightning.App
import com.github.seratch.jslack.lightning.AppConfig
import com.github.seratch.jslack.lightning.util.JsonOps
import example.database.AppHomeSettings
import example.database.Database
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
        const val selectAppOverlayMenu = "select-app-overlay-menu"
        const val viewInBrowserButton = "view-in-browser-button"
    }

    @Bean
    fun loadAppConfig(): AppConfig = FileConfigLoader.loadAppConfig()

    @Bean
    fun initApp(appConfig: AppConfig): App {

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
            val appHomeSettings = Database.find(slackUserId)
            val newRelicApi = newRelicRestApi(appHomeSettings?.restApiKey)
            updateAppHome(slackUserId, appHomeSettings, ctx.client(), newRelicApi)
            ctx.ack()
        }

        app.blockAction(ActionIds.selectAppOverlayMenu) { req, ctx ->
            val slackUserId = req.payload.user.id
            val appId = req.payload.actions[0].selectedOption.value.toInt()
            val row = Database.find(slackUserId)
            if (row != null) {
                row.defaultApplicationId = appId
                Database.save(row)
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
            val appHomeSettings = Database.find(slackUserId)
            val view = buildSettingsModalView(appHomeSettings)
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
                saveAndViewsPublish(slackUserId, accountId!!, restApiKey!!, queryApiKey!!, ctx.client())
                ctx.ack() // close
            }
        }

        app.blockAction(ActionIds.clearSettingsButton) { req, ctx ->
            val slackUserId = req.payload.user.id
            Database.delete(slackUserId)
            updateAppHome(slackUserId, null, ctx.client(), null)
            ctx.ack()
        }

        // --------------------------
        // NRQL Query Runner
        // --------------------------

        app.blockAction(ActionIds.queryButton) { req, ctx ->
            val slackUserId = req.payload.user.id
            val triggerId = req.payload.triggerId
            val appHomeSettings = Database.find(slackUserId)
            val newRelic = newRelicRestApi(appHomeSettings?.restApiKey)
            val accountId = appHomeSettings?.accountId
            val applicationId = appHomeSettings?.defaultApplicationId
                    ?: newRelic?.applicationsList()?.applications?.first()?.id
            val query = buildQuery(null, applicationId)
            val insightsApi = newRelicInsightsApi(appHomeSettings)!!
            val view = buildQueryModalView(
                    accountId,
                    applicationId,
                    query,
                    insightsApi.runQuery(query))
            viewsOpen(triggerId, view, ctx.client())
            ctx.ack()
        }

        app.viewSubmission(CallbackIds.queryModal) { req, ctx ->
            val slackUserId = req.payload.user.id
            val state = req.payload.view.state.values
            val appHomeSettings = Database.find(slackUserId)
            val query = buildQuery(
                    state?.get(BlockIds.inputQuery)?.get(ActionIds.input)?.value,
                    appHomeSettings?.defaultApplicationId
            )
            val insightsApi = newRelicInsightsApi(appHomeSettings)!!
            val view = buildQueryModalView(
                    appHomeSettings?.accountId,
                    appHomeSettings?.defaultApplicationId,
                    query,
                    insightsApi.runQuery(query)
            )

            if (logger.isDebugEnabled) {
                logger.debug("Updating a view by responding to a view_submission request\n\n${JsonOps.toJsonString(view)}\n")
            }
            // Will update the current modal
            ctx.ack { it.responseAction("update").view(view) }
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


    private fun newRelicInsightsApi(appHomeSettings: AppHomeSettings?): NewRelicInsightsApi? =
            if (appHomeSettings?.queryApiKey != null) {
                NewRelicInsightsApi(appHomeSettings.accountId!!, appHomeSettings.queryApiKey!!)
            } else null

    private fun viewsOpen(triggerId: String, view: View?, client: MethodsClient): ViewsOpenResponse? {
        if (logger.isDebugEnabled) {
            logger.debug("Going to send this view to views.open API\n\n${JsonOps.toJsonString(view)}\n")
        }
        return client.viewsOpen { it.triggerId(triggerId).view(view) }
    }

    private fun viewsPublish(blocks: List<LayoutBlock>, client: MethodsClient, slackUserId: String): ViewsPublishResponse? {
        val view = View.builder()
                .type("home")
                .blocks(blocks)
                .build()

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
            client: MethodsClient) {
        GlobalScope.async {
            val row = Database.find(slackUserId)
            if (row != null) {
                row.accountId = accountId
                row.restApiKey = restApiKey
                row.queryApiKey = queryApiKey
                Database.save(row)
                val newRelicApi = newRelicRestApi(row.restApiKey)
                updateAppHome(slackUserId, row, client, newRelicApi)
            } else {
                val newRelicApi = newRelicRestApi(restApiKey)
                val newRow = AppHomeSettings(
                        slackUserId = slackUserId,
                        accountId = accountId,
                        restApiKey = restApiKey,
                        queryApiKey = queryApiKey,
                        defaultApplicationId = newRelicApi?.applicationsList()?.applications?.first()?.id
                )
                Database.save(newRow)
                updateAppHome(slackUserId, newRow, client, newRelicApi)
            }
        }
    }

    private val emptyListOfBlock = emptyList<LayoutBlock>()

    private fun updateAppHome(
            slackUserId: String,
            appHomeSettings: AppHomeSettings?,
            client: MethodsClient,
            newRelicRestApi: NewRelicRestApi?) {

        if (newRelicRestApi == null) {
            // need initialize
            viewsPublish(listOf(
                    SectionBlock.builder()
                            .text(markdownTextObject("*:loud_sound: Unlock your personalized :new-relic: dashboard!*")).build(),
                    ActionsBlock.builder()
                            .elements(listOf(ButtonElement.builder()
                                    .actionId(ActionIds.settingsButton)
                                    .style("primary")
                                    .text(plainTextObject("Enable Now"))
                                    .build()
                            )).build()), client, slackUserId)
            return
        }

        val headerBlock = SectionBlock.builder()
                .text(markdownTextObject("*:new-relic: New Relic Dashboard :new-relic:*"))
                .accessory(ButtonElement.builder()
                        .actionId(ActionIds.clearSettingsButton)
                        .style("danger")
                        .text(plainTextObject("Clear Settings"))
                        .confirm(ConfirmationDialogObject.builder()
                                .title(plainTextObject("Clear Settings"))
                                .text(plainTextObject("Are you sure?")).build())
                        .build())
                .build()

        val queryBlock = ActionsBlock.builder().elements(listOf(ButtonElement.builder()
                .actionId(ActionIds.queryButton)
                .text(plainTextObject(":pencil: Query Runner"))
                .build()
        )).build()

        val applications = newRelicRestApi.applicationsList()!!.applications
        val appSelectorBlock = SectionBlock.builder()
                .text(plainTextObject("Select Application :arrow_right:"))
                .accessory(OverflowMenuElement.builder()
                        .actionId(ActionIds.selectAppOverlayMenu)
                        .options(applications.map { app ->
                            OptionObject.builder()
                                    .text(plainTextObject(app.name))
                                    .value(app.id.toString())
                                    .build()
                        })
                        .build()
                ).build()

        if (applications.isEmpty()) {
            viewsPublish(listOf(headerBlock, appSelectorBlock), client, slackUserId)
            return
        }

        val dividerBlock = DividerBlock.builder().build()

        val firstApp = applications.first()
        val currentApp =
                if (appHomeSettings != null) {
                    val filteredApps = applications.filter { it.id == appHomeSettings.defaultApplicationId }
                    if (filteredApps.isEmpty()) firstApp else filteredApps.first()
                } else firstApp

        val appHeaderBlock = SectionBlock.builder().text(markdownTextObject("*:mag: Application*")).build()

        val appHealthStatus = if (currentApp.healthStatus == "red") ":red_circle:" else ":large_blue_circle:"
        val date =
                if (currentApp.lastReportedAt != null) ZonedDateTime.parse(currentApp.lastReportedAt, DateTimeFormatter.ISO_OFFSET_DATE_TIME).withZoneSameInstant(zoneId)
                else null
        val appBlock = SectionBlock.builder()
                .text(markdownTextObject("""
                        Name: *${currentApp.name}*
                        Language: *:${currentApp.language}:*
                        Health Status: *${appHealthStatus}*
                        Last Reported: *${date?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) ?: "-"}*
                        """.trimIndent()))
                .accessory(ButtonElement.builder()
                        .actionId(ActionIds.viewInBrowserButton)
                        .text(plainTextObject("View in browser"))
                        .url("https://rpm.newrelic.com/accounts/${appHomeSettings!!.accountId}/applications/${currentApp.id}")
                        .build())
                .build()

        val hostsHeaderBlock = SectionBlock.builder()
                .text(markdownTextObject("*:electric_plug: Hosts*"))
                .build()

        val hosts = newRelicRestApi.applicationHostsList(currentApp.id)!!.applicationHosts
        val hostsBlocks =
                if (hosts.isNotEmpty()) {
                    val hostsBlock = SectionBlock.builder().fields(hosts.take(10).map { host ->
                        val hostHealthStatus = if (host.healthStatus == "red") ":red_circle:" else ":large_blue_circle:"
                        markdownTextObject("""
                            Host: *${host.host}*
                            Health Status: *${hostHealthStatus}*
                        """.trimIndent())
                    }).build()
                    listOf(hostsHeaderBlock, dividerBlock, hostsBlock)
                } else emptyListOfBlock

        val violationsHeaderBlock = SectionBlock.builder()
                .text(markdownTextObject("*:warning: Alert Violations*"))
                .accessory(ButtonElement.builder()
                        .actionId(ActionIds.viewInBrowserButton)
                        .text(plainTextObject("View in browser"))
                        .url("https://rpm.newrelic.com/accounts/${appHomeSettings.accountId}/applications/${currentApp.id}/violations")
                        .build()
                ).build()
        val violations = newRelicRestApi.alertsViolationsList(currentApp.id)!!.violations
        val violationsBlock = SectionBlock.builder()
                .fields(violations.take(10).map { v ->
                    val opened = ZonedDateTime.ofInstant(Date(v.openedAt).toInstant(), zoneId)
                    markdownTextObject("""
                        Priority: *${v.priority}*
                        Violation: *${v.label}*
                        Opened: *${opened.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}*
                    """.trimIndent())
                }).build()

        val violationsBlocks =
                if (violations.isNotEmpty()) listOf(violationsHeaderBlock, dividerBlock, violationsBlock)
                else emptyListOfBlock

        val blocks =
                listOf(headerBlock, queryBlock, dividerBlock, appSelectorBlock, appHeaderBlock, dividerBlock, appBlock) +
                        hostsBlocks + violationsBlocks
        viewsPublish(blocks, client, slackUserId)
    }

    // --------------
    // Building Modals

    private fun buildSettingsModalView(appHomeSettings: AppHomeSettings?): View? {
        return View.builder()
                .type("modal")
                .callbackId(CallbackIds.settingsModal)
                .submit(ViewSubmit.builder().type("plain_text").text("Save").build())
                .close(ViewClose.builder().type("plain_text").text("Close").build())
                .title(ViewTitle.builder().type("plain_text").text("New Relic Settings").build())
                .blocks(listOf(
                        InputBlock.builder()
                                .blockId(BlockIds.inputAccountId)
                                .label(plainTextObject("Account Id"))
                                .element(PlainTextInputElement.builder()
                                        .actionId(ActionIds.input)
                                        .placeholder(plainTextObject("Check rpm.newrelic.com/accounts/"))
                                        .initialValue(appHomeSettings?.accountId)
                                        .build())
                                .optional(false)
                                .build(),
                        InputBlock.builder()
                                .blockId(BlockIds.inputRestApiKey)
                                .label(plainTextObject("REST API Key"))
                                .element(PlainTextInputElement.builder()
                                        .actionId(ActionIds.input)
                                        .placeholder(plainTextObject("Check rpm.newrelic.com/accounts/{id}/integrations?page=api_keys"))
                                        .initialValue(appHomeSettings?.restApiKey ?: "NRRA-")
                                        .build())
                                .optional(false)
                                .build(),
                        InputBlock.builder()
                                .blockId(BlockIds.inputQueryApiKey)
                                .label(plainTextObject("Insights Query API Key"))
                                .element(PlainTextInputElement.builder()
                                        .actionId(ActionIds.input)
                                        .placeholder(plainTextObject("Check insights.newrelic.com/accounts/{id}}/manage/api_keys"))
                                        .initialValue(appHomeSettings?.queryApiKey ?: "NRIQ-")
                                        .build())
                                .optional(false)
                                .build()
                ))
                .build()
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
        return View.builder()
                .type("modal")
                .callbackId(CallbackIds.queryModal)
                .submit(ViewSubmit.builder().type("plain_text").text("Run").build())
                .close(ViewClose.builder().type("plain_text").text("Close").build())
                .title(ViewTitle.builder().type("plain_text").text("Insights Query Runner").build())
                .blocks(listOf(
                        ActionsBlock.builder()
                                .elements(listOf(ButtonElement.builder()
                                        .actionId(ActionIds.viewInBrowserButton)
                                        .text(plainTextObject("NRQL: New Relic Query Language"))
                                        .url("https://docs.newrelic.com/docs/query-data/nrql-new-relic-query-language/getting-started/nrql-syntax-components-functions")
                                        .build()))
                                .build(),
                        InputBlock.builder()
                                .blockId(BlockIds.inputQuery)
                                .label(plainTextObject("Query (NRQL)"))
                                .element(PlainTextInputElement.builder()
                                        .actionId(ActionIds.input)
                                        .placeholder(plainTextObject("Write an NRQL query here"))
                                        .initialValue(queryToRun)
                                        .multiline(true)
                                        .build())
                                .optional(false)
                                .build()
                ) + buildQueryResultBlocks(accountId, queryToRun, queryResponse))
                .build()
    }

    private fun buildQueryResultBlocks(accountId: String?, query: String?, queryResponse: QueryResponse?): List<LayoutBlock> {
        val blocks = mutableListOf<LayoutBlock>()
        val dividerBlock = DividerBlock.builder().build()
        if (queryResponse?.error != null) {
            blocks.add(dividerBlock)
            blocks.add(SectionBlock.builder().text(markdownTextObject(queryResponse.error)).build())
        } else if (queryResponse?.results != null) {
            for (result in queryResponse.results) {
                if (result["events"] != null) {
                    if ((result["events"] ?: error("")).isNotEmpty()) {
                        val events = (result["events"] ?: error("")).take(20)
                        for (event in events) {
                            blocks.add(dividerBlock)
                            val block = SectionBlock.builder().text(markdownTextObject(event.keys.joinToString("\n") { key -> "$key: *${event[key]}*" })).build()
                            blocks.add(block)
                        }
                    } else {
                        blocks.add(dividerBlock)
                        val block = SectionBlock.builder().text(markdownTextObject("No data found.")).build()
                        blocks.add(block)
                    }
                } else if (result.size == 1) {
                    val res = result.values.first()[0]
                    blocks.add(dividerBlock)
                    val block = SectionBlock.builder().text(markdownTextObject(res.keys.joinToString("\n") { key -> "$key: *${res[key]}*" })).build()
                    blocks.add(block)
                }
            }
        }
        if (accountId != null) {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            blocks.add(dividerBlock)
            blocks.add(ActionsBlock.builder().elements(listOf(ButtonElement.builder()
                    .actionId(ActionIds.viewInBrowserButton)
                    .text(plainTextObject("View in browser"))
                    .url("https://insights.newrelic.com/accounts/$accountId/query?query=$encodedQuery")
                    .build()
            )).build())
        }
        return blocks
    }

    // ------------------------------------------------

    private fun plainTextObject(text: String) = PlainTextObject.builder().text(text).build()

    private fun markdownTextObject(text: String?) = MarkdownTextObject.builder().text(text).build()

}