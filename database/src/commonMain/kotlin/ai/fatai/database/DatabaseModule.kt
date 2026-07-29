package ai.fatai.database

import app.cash.sqldelight.ColumnAdapter
import ai.fatai.database.WatsonDatabase
import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.database.sqldelight.ChatItem
import ai.fatai.database.sqldelight.Conversation
import ai.fatai.database.sqldelight.ApiKey
import ai.fatai.bean.ChatItemType
import ai.fatai.bean.MessageContentType
import ai.fatai.chat.ProviderType

class Database(databaseDriverFactory: DatabaseDriverFactory) {
    val watsonQueries: WatsonQueries = WatsonDatabase(
        driver = databaseDriverFactory.createDriver(),
        ChatItemAdapter = ChatItem.Adapter(
            typeAdapter = chatItemTypeAdapter,
            contentTypeAdapter = messageContentTypeAdapter
        ),
        ConversationAdapter = Conversation.Adapter(providerTypeAdapter = providerTypeAdapter),
        ApiKeyAdapter = ApiKey.Adapter(providerTypeAdapter = providerTypeAdapter)
    ).watsonQueries
}

val chatItemTypeAdapter = object : ColumnAdapter<ChatItemType, String> {
    override fun encode(value: ChatItemType): String = value.name
    override fun decode(databaseValue: String): ChatItemType = ChatItemType.valueOf(databaseValue)
}

val messageContentTypeAdapter = object : ColumnAdapter<MessageContentType, String> {
    override fun encode(value: MessageContentType): String = value.name
    override fun decode(databaseValue: String): MessageContentType = MessageContentType.valueOf(databaseValue)
}

val providerTypeAdapter = object : ColumnAdapter<ProviderType, String> {
    override fun encode(value: ProviderType): String = value.name
    override fun decode(databaseValue: String): ProviderType = ProviderType.valueOf(databaseValue)
}
