package com.example.folio

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val UserDocumentListKey = "documents"

internal data class LibraryDocument(
    val title: String,
    val subtitle: String,
    val kind: DocumentKind,
    val sourceUri: String? = null
)

internal suspend fun rememberImportedDocument(
    context: android.content.Context,
    documents: MutableList<LibraryDocument>,
    document: DocumentState
) {
    val updated = updatedImportedDocuments(documents, document)
    documents.clear()
    documents.addAll(updated)
    saveUserDocuments(context, documents)
}

internal fun updatedImportedDocuments(
    documents: List<LibraryDocument>,
    document: DocumentState,
    importedAt: String = formatImportedAt()
): List<LibraryDocument> {
    val imported = LibraryDocument(
        title = document.title,
        subtitle = "我的手机 • $importedAt",
        kind = document.kind,
        sourceUri = document.sourceUri
    )
    return buildList {
        add(imported)
        addAll(documents.filterNot { it.sourceUri == document.sourceUri }.take(49))
    }
}

internal suspend fun loadUserDocuments(context: android.content.Context): List<LibraryDocument> {
    val key = stringPreferencesKey(UserDocumentListKey)
    val raw = context.readerPreferencesDataStore.data.map { prefs -> prefs[key] }.first()
        ?: migrateLegacyUserDocuments(context)
        ?: return emptyList()

    return parseUserDocuments(raw)
}

private fun parseUserDocuments(raw: String): List<LibraryDocument> {
    return runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title").takeIf { it.isNotBlank() } ?: continue
                if (!isSupportedDocumentName(title)) continue
                val sourceUri = item.optString("sourceUri").takeIf { it.isNotBlank() } ?: continue
                val kind = runCatching {
                    DocumentKind.valueOf(item.optString("kind"))
                }.getOrElse {
                    DocumentKind.Markdown
                }
                add(
                    LibraryDocument(
                        title = title,
                        subtitle = sanitizeLibrarySubtitle(item.optString("subtitle", "")),
                        kind = kind,
                        sourceUri = sourceUri
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}

internal suspend fun saveUserDocuments(context: android.content.Context, documents: List<LibraryDocument>) {
    val array = JSONArray()
    documents
        .filter { it.sourceUri != null }
        .take(50)
        .forEach { item ->
            array.put(
                JSONObject()
                    .put("title", item.title)
                    .put("subtitle", item.subtitle)
                    .put("kind", item.kind.name)
                    .put("sourceUri", item.sourceUri)
            )
        }
    context.readerPreferencesDataStore.edit { prefs ->
        prefs[stringPreferencesKey(UserDocumentListKey)] = array.toString()
    }
}

private suspend fun migrateLegacyUserDocuments(context: android.content.Context): String? {
    val legacy = legacyUserDocumentsPreferences(context)
    val raw = legacy.getString(UserDocumentListKey, null) ?: return null
    context.readerPreferencesDataStore.edit { prefs ->
        prefs[stringPreferencesKey(UserDocumentListKey)] = raw
    }
    legacy.edit().remove(UserDocumentListKey).apply()
    return raw
}

private fun legacyUserDocumentsPreferences(context: android.content.Context) =
    context.getSharedPreferences("folio_user_documents", android.content.Context.MODE_PRIVATE)

private fun formatImportedAt(): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date())

private fun sanitizeLibrarySubtitle(value: String): String {
    val importedAt = value.substringAfter("•", "").trim()
    return if (importedAt.isNotBlank()) {
        "我的手机 • $importedAt"
    } else {
        "我的手机"
    }
}
