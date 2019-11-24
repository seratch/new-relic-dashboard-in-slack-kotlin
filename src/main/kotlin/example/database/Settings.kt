package example.database

data class Settings(
        val slackUserId: String,
        var accountId: String?,
        var defaultApplicationId: Int?,
        var restApiKey: String?,
        var queryApiKey: String?
)