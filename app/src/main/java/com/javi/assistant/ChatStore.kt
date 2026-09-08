package com.javi.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private const val PREFS = "javi_chat_history"
private const val KEY = "conversations"
private const val RETENTION_MS = 60L * 24L * 60L * 60L * 1000L

data class StoredConversation(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messages: List<UiMessage>
)

object ChatStore {
    fun newId(): String = UUID.randomUUID().toString()

    fun load(context: Context): List<StoredConversation> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val now = System.currentTimeMillis()
        val result = mutableListOf<StoredConversation>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val updatedAt = c.optLong("updatedAt", now)
                if (now - updatedAt > RETENTION_MS) continue
                val msgs = mutableListOf<UiMessage>()
                val ma = c.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until ma.length()) {
                    val m = ma.getJSONObject(j)
                    msgs += UiMessage(
                        role = m.optString("role", "assistant"),
                        content = m.optString("content", ""),
                        imageBase64 = null
                    )
                }
                result += StoredConversation(
                    id = c.optString("id").ifBlank { newId() },
                    title = c.optString("title", "Conversación"),
                    updatedAt = updatedAt,
                    messages = msgs
                )
            }
        }
        saveAll(context, result)
        return result.sortedByDescending { it.updatedAt }
    }

    fun save(context: Context, conversation: StoredConversation) {
        val all = load(context).toMutableList()
        all.removeAll { it.id == conversation.id }
        all.add(conversation.copy(messages = conversation.messages.map { it.copy(imageBase64 = null) }))
        saveAll(context, all.sortedByDescending { it.updatedAt })
    }

    fun delete(context: Context, id: String) {
        saveAll(context, load(context).filterNot { it.id == id })
    }

    private fun saveAll(context: Context, conversations: List<StoredConversation>) {
        val arr = JSONArray()
        conversations.forEach { c ->
            val ma = JSONArray()
            c.messages.forEach { m ->
                ma.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                })
            }
            arr.put(JSONObject().apply {
                put("id", c.id)
                put("title", c.title)
                put("updatedAt", c.updatedAt)
                put("messages", ma)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
