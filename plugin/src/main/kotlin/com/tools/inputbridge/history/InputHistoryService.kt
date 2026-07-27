package com.tools.inputbridge.history

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

@Service(Service.Level.APP)
@State(
    name = "com.tools.inputbridge.history",
    storages = [Storage(value = "InputBridgeHistory.xml", roamingType = RoamingType.DISABLED)],
)
class InputHistoryService : PersistentStateComponent<InputHistoryService.StoredState> {
    private var storedState = StoredState()

    @Synchronized
    override fun getState(): StoredState = storedState.deepCopy()

    @Synchronized
    override fun loadState(state: StoredState) {
        storedState = sanitize(state)
    }

    @Synchronized
    internal fun history(): List<InputHistoryItem> =
        storedState.entries.map(StoredHistoryItem::toHistoryItem)

    @Synchronized
    internal fun preferences(): InputHistoryPreferences =
        InputHistoryPreferences(storedState.enabled, storedState.maxEntries)

    @Synchronized
    internal fun recordSuccessfulInput(content: String, nowMillis: Long = System.currentTimeMillis()): Boolean {
        if (!storedState.enabled) return false
        val normalized = InputHistoryRules.validate(content) ?: return false
        val updated = buildList {
            add(InputHistoryItem(normalized, nowMillis))
            history().filterTo(this) { it.content != normalized }
        }
        storedState.entries = InputHistoryRules.trim(updated, storedState.maxEntries)
            .mapTo(mutableListOf(), StoredHistoryItem::fromHistoryItem)
        return true
    }

    @Synchronized
    internal fun updatePreferences(enabled: Boolean, maxEntries: Int) {
        storedState.enabled = enabled
        storedState.maxEntries = InputHistoryRules.normalizeLimit(maxEntries)
        storedState.entries = InputHistoryRules.trim(history(), storedState.maxEntries)
            .mapTo(mutableListOf(), StoredHistoryItem::fromHistoryItem)
    }

    @Synchronized
    internal fun clear() {
        storedState.entries.clear()
    }

    @Synchronized
    internal fun size(): Int = storedState.entries.size

    private fun sanitize(state: StoredState): StoredState {
        val limit = InputHistoryRules.normalizeLimit(state.maxEntries)
        return StoredState().apply {
            version = CURRENT_VERSION
            enabled = state.enabled
            maxEntries = limit
            entries = InputHistoryRules.trim(
                state.entries.map(StoredHistoryItem::toHistoryItem),
                limit,
            ).mapTo(mutableListOf(), StoredHistoryItem::fromHistoryItem)
        }
    }

    class StoredState {
        var version: Int = CURRENT_VERSION
        var enabled: Boolean = true
        var maxEntries: Int = InputHistoryRules.DEFAULT_MAX_ENTRIES
        var entries: MutableList<StoredHistoryItem> = mutableListOf()

        fun deepCopy(): StoredState = StoredState().also { copy ->
            copy.version = version
            copy.enabled = enabled
            copy.maxEntries = maxEntries
            copy.entries = entries.mapTo(mutableListOf(), StoredHistoryItem::copy)
        }
    }

    class StoredHistoryItem {
        var content: String = ""
        var lastUsedAtMillis: Long = 0

        fun copy(): StoredHistoryItem = StoredHistoryItem().also { copy ->
            copy.content = content
            copy.lastUsedAtMillis = lastUsedAtMillis
        }

        fun toHistoryItem(): InputHistoryItem = InputHistoryItem(content, lastUsedAtMillis)

        companion object {
            fun fromHistoryItem(item: InputHistoryItem): StoredHistoryItem = StoredHistoryItem().also {
                it.content = item.content
                it.lastUsedAtMillis = item.lastUsedAtMillis
            }
        }
    }

    private companion object {
        const val CURRENT_VERSION = 1
    }
}
