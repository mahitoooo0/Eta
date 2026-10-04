package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Branch-only attachment relocation. Never searches prose, tool literals or unknown JSON fields. */
internal object AgentConversationAttachmentRelocator {
    fun rewrite(
        message: AgentModelClient.ConversationMessage,
        path: (String) -> String,
    ): AgentModelClient.ConversationMessage {
        val structured = message.contentJson.isNotBlank()
        val content = if (structured) {
            runCatching { JSONTokener(message.contentJson).nextValue() }.getOrNull() ?: return message
        } else message.content
        val json = JSONObject().put("role", message.role).put("content", content)
        if (!rewriteJsonMessage(json, path)) return message
        return if (structured) message.copy(contentJson = json.get("content").toString())
        else message.copy(content = json.getString("content"))
    }

    /** Mutates only content's recognized attachment slots; preserves all other archive fields. */
    fun rewriteJsonMessage(message: JSONObject, path: (String) -> String): Boolean {
        val role = message.optString("role")
        if (role.equals("tool", ignoreCase = true) || role.equals("function", ignoreCase = true)) return false
        val user = role.equals("user", ignoreCase = true)
        fun rewriteString(owner: JSONObject, key: String, transform: (String) -> String): Boolean {
            val before = owner.opt(key) as? String ?: return false
            val after = transform(before)
            if (after == before) return false
            owner.put(key, after)
            return true
        }
        fun localPath(value: String): String =
            if (value.startsWith("/") || value.startsWith("file:///")) path(value) else value
        fun block(item: JSONObject): Boolean = when (item.optString("type")) {
            AgentConversationCodec.IMAGE_FILE_TYPE, AgentConversationCodec.VIDEO_FILE_TYPE ->
                rewriteString(item, "path", ::localPath)
            "image_url" -> item.optJSONObject("image_url")?.let {
                rewriteString(it, "url", ::localPath)
            } ?: false
            "text" -> user && rewriteString(item, "text") {
                AgentFileReferencePromptCodec.rewriteReferencePaths(it, path)
            }
            else -> false
        }
        return when (val content = message.opt("content")) {
            is String -> user && rewriteString(message, "content") {
                AgentFileReferencePromptCodec.rewriteReferencePaths(it, path)
            }
            is JSONObject -> block(content)
            is JSONArray -> {
                var changed = false
                for (index in 0 until content.length()) {
                    val item = content.optJSONObject(index) ?: continue
                    if (block(item)) changed = true
                }
                changed
            }
            else -> false
        }
    }
}
