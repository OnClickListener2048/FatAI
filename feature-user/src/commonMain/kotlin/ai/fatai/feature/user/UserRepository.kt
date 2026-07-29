package ai.fatai.feature.user

import ai.fatai.database.sqldelight.WatsonQueries
import kotlin.time.Clock

const val DEFAULT_USER_ID = "local-default"
private const val DEFAULT_USER_NAME = "Default User"

/**
 * Provides the owner of data accessed by repositories.
 *
 * Login can later replace this implementation without changing feature repositories.
 */
interface CurrentUserProvider {
    val currentUserId: String
}

data class User(
    val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long
)

class UserRepository(
    private val queries: WatsonQueries
) : CurrentUserProvider {
    override val currentUserId: String = DEFAULT_USER_ID

    init {
        ensureDefaultUser()
    }

    fun currentUser(): User = queries.selectUserById(currentUserId)
        .executeAsOneOrNull()
        ?.let { User(it.id, it.name, it.createdAt, it.updatedAt) }
        ?: error("Default user is unavailable")

    private fun ensureDefaultUser() {
        if (queries.selectUserById(DEFAULT_USER_ID).executeAsOneOrNull() != null) return
        val time = now()
        queries.insertUser(DEFAULT_USER_ID, DEFAULT_USER_NAME, time, time)
    }

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()
}
