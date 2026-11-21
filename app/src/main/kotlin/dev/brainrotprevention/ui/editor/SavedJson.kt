package dev.brainrotprevention.ui.editor

import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.json.Json

/*
 * Editor drafts in SavedStateHandle as JSON, so a draft survives process death (a long editor session is not
 * lost when Android kills the app in the background). Very large drafts (bulk-imported lists) are not stored, so
 * the saved state stays well below the Binder transaction limit; those fall back to the database copy.
 */
internal val stateJson = Json { ignoreUnknownKeys = true }

internal const val MAX_SAVED_JSON = 100_000

internal inline fun <reified T> SavedStateHandle.getJson(key: String): T? =
    get<String>(key)?.let { runCatching { stateJson.decodeFromString<T>(it) }.getOrNull() }

internal inline fun <reified T> SavedStateHandle.setJson(key: String, value: T?) {
    val text = value?.let { stateJson.encodeToString(it) }
    if (text == null || text.length > MAX_SAVED_JSON) remove<String>(key) else set(key, text)
}
