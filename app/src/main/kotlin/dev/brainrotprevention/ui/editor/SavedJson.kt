package dev.brainrotprevention.ui.editor

import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.json.Json

/*
 * Editor drafts in SavedStateHandle as JSON, so a draft survives process death (a long editor session is not
 * lost when Android kills the app in the background). Very large drafts (bulk-imported lists) are not stored, so
 * the saved state stays well below the Binder transaction limit; those fall back to the database copy.
 */
internal val stateJson = Json { ignoreUnknownKeys = true }

/**
 * Budget for all editor keys together, in Parcel bytes (strings are UTF-16 in a Parcel, 2 bytes per char), well below
 * the ~1 MB Binder limit that also holds the nav back stack (review round 2, diff minor 4).
 */
internal const val MAX_SAVED_BYTES = 64_000

/** Every key the editors store; [setJson] keeps their sum within [MAX_SAVED_BYTES]. */
internal val EDITOR_KEYS = listOf("editor_draft", "editor_initial", "editor_web_input", "qb_draft", "qb_initial", "qb_web_input")

internal fun parcelBytes(s: String): Int = 2 * s.length + 8

internal inline fun <reified T> SavedStateHandle.getJson(key: String): T? =
    get<String>(key)?.let { runCatching { stateJson.decodeFromString<T>(it) }.getOrNull() }

/** Stores [value] as JSON, or removes the key when it is null or would push the editor keys over the budget. */
internal inline fun <reified T> SavedStateHandle.setJson(key: String, value: T?) {
    val text = value?.let { stateJson.encodeToString(it) }
    val others = EDITOR_KEYS.filter { it != key }.sumOf { k -> get<String>(k)?.let(::parcelBytes) ?: 0 }
    if (text == null || others + parcelBytes(text) > MAX_SAVED_BYTES) remove<String>(key) else set(key, text)
}
