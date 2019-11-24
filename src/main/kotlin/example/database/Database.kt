package example.database

interface Database {

    fun saveSettings(entity: Settings)

    fun findSettings(slackUserId: String): Settings?

    fun deleteAll(slackUserId: String)

    fun saveQuery(slackUserId: String, query: String?)

    fun findQueries(slackUserId: String): List<String>

}