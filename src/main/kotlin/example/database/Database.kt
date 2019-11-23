package example.database

interface Database {

    fun save(entity: AppHomeSettings)

    fun find(slackUserId: String): AppHomeSettings?

    fun delete(slackUserId: String)

}