package io.github.nanomuse.goals

import com.openminis.app.MinisApp
import com.openminis.app.data.MemoryGlobalPrefs

/**
 * A goal's own conversation. Mirrors what ScheduledAgentRunner does for a NewSession task: seed
 * the row with a model from the default group (credentialed members only), bind the group so the
 * chat boots through it, and fall back to the first visible entry.
 */
object GoalSessions {
    suspend fun create(app: MinisApp, title: String, source: String = "scheduled"): String? {
        val owner = io.github.nanomuse.account.AccountData.key(app)
        val providers = app.providerRepository
        val defaultGroupId = providers.defaultPrimaryGroupId
        val seedModelId = defaultGroupId
            ?.let { providers.group(it) }
            ?.let { g -> providers.availableMemberEntries(g).firstOrNull()?.model?.id }
            ?: providers.allVisibleEntries().firstOrNull()?.baseModel?.id
            ?: return null
        val session = app.chatRepository.createSession(
            modelId = seedModelId,
            title = title,
            memoryEnabled = MemoryGlobalPrefs.isGlobalEnabled(app),
            source = source,
        )
        if (source == io.github.nanomuse.feed.FeedChatOutput.SOURCE) io.github.nanomuse.account.AccountData.claimNow(app, session.id, owner)
        if (defaultGroupId != null) {
            app.chatRepository.updateSessionBinding(
                session.id,
                """{"type":"group","groupId":"$defaultGroupId"}""",
                seedModelId,
            )
        }
        return session.id
    }
}
