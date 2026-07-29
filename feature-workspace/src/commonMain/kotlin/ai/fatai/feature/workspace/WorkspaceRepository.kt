package ai.fatai.feature.workspace

import ai.fatai.database.sqldelight.WatsonQueries
import ai.fatai.feature.user.CurrentUserProvider
import ai.fatai.feature.user.DEFAULT_USER_ID
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

const val INBOX_WORKSPACE_ID = "inbox"

fun inboxWorkspaceIdFor(userId: String): String =
    if (userId == DEFAULT_USER_ID) INBOX_WORKSPACE_ID else "$userId:$INBOX_WORKSPACE_ID"

data class Workspace(
    val id: String,
    val userId: String,
    val name: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isArchived: Boolean
)

class WorkspaceRepository(private val queries: WatsonQueries, private val currentUser: CurrentUserProvider) {
    val inboxWorkspaceId: String get() = inboxWorkspaceIdFor(currentUser.currentUserId)

    @OptIn(kotlin.time.ExperimentalTime::class)
    private fun now() = Clock.System.now().toEpochMilliseconds()

    fun ensureInbox(): Workspace {
        return getById(inboxWorkspaceId) ?: Workspace(
            id = inboxWorkspaceId,
            userId = currentUser.currentUserId,
            name = "Personal",
            systemPrompt = "",
            createdAt = now(),
            updatedAt = now(),
            isArchived = false
        ).also { workspace ->
            queries.insertWorkspace(
                id = workspace.id,
                userId = workspace.userId,
                name = workspace.name,
                systemPrompt = workspace.systemPrompt,
                createdAt = workspace.createdAt,
                updatedAt = workspace.updatedAt,
                isArchived = 0L
            )
        }
    }

    fun getAll(): List<Workspace> = queries.selectWorkspaces(currentUser.currentUserId).executeAsList().map { it.toWorkspace() }

    fun getById(id: String): Workspace? = queries.selectWorkspaceById(id, currentUser.currentUserId).executeAsOneOrNull()?.toWorkspace()

    @OptIn(ExperimentalUuidApi::class)
    fun create(name: String, systemPrompt: String = ""): Workspace {
        val time = now()
        val workspace = Workspace(Uuid.random().toString(), currentUser.currentUserId, name.trim(), systemPrompt.trim(), time, time, false)
        queries.insertWorkspace(
            id = workspace.id,
            userId = workspace.userId,
            name = workspace.name,
            systemPrompt = workspace.systemPrompt,
            createdAt = time,
            updatedAt = time,
            isArchived = 0L
        )
        return workspace
    }

    fun update(id: String, name: String, systemPrompt: String) {
        queries.updateWorkspace(name.trim(), systemPrompt.trim(), now(), id, currentUser.currentUserId)
    }

    fun archive(id: String, archived: Boolean) {
        if (id != inboxWorkspaceId) queries.archiveWorkspace(if (archived) 1L else 0L, now(), id, currentUser.currentUserId)
    }
}

private fun ai.fatai.database.sqldelight.Workspace.toWorkspace() = Workspace(
    id = id,
    userId = userId,
    name = name,
    systemPrompt = systemPrompt,
    createdAt = createdAt,
    updatedAt = updatedAt,
    isArchived = isArchived != 0L
)
