package example.database

data class AppHomeSettings(
        val slackUserId: String,
        var accountId: String?,
        var defaultApplicationId: Int?,
        var restApiKey: String?,
        var queryApiKey: String?
)