package io.github.mangi.eta.agent.media

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import java.io.File
import java.util.UUID

/**
 * 将原始附件落盘，UI 预览和模型能力相互独立。纯文本模型只能接收路径，不能通过 read_image 获得视觉能力。
 * 不写入用户工作区；删除会话或变成孤儿后清理，避免缓存无限涨。
 */
internal class AgentChatImageCache(context: Context) {
    private val root = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)
    private val packageName = context.applicationContext.packageName

    fun stage(
        conversationId: String,
        bytes: ByteArray,
        displayName: String,
        maxBytes: Int = MAX_AGENT_IMAGE_BYTES,
    ): AgentFileReference? {
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        val conversationDir = conversationDir(conversationId) ?: return null
        val safeName = AgentFileReferenceGateway.safeImportName(displayName)
        val destination = File(conversationDir, "${UUID.randomUUID()}-$safeName")
        destination.writeBytes(bytes)
        if (!destination.isFile) return null
        return AgentFileReference(
            displayName = safeName,
            absolutePath = destination.absolutePath,
            kind = AgentFileReferenceKind.File,
        )
    }

    fun stageFromFile(
        conversationId: String,
        source: File,
        displayName: String,
        maxBytes: Int = MAX_AGENT_VIDEO_BYTES,
    ): AgentFileReference? {
        if (!source.isFile || source.length() !in 1L..maxBytes.toLong()) return null
        val conversationDir = conversationDir(conversationId) ?: return null
        val safeName = AgentFileReferenceGateway.safeImportName(displayName)
        val destination = File(conversationDir, "${UUID.randomUUID()}-$safeName")
        source.copyTo(destination, overwrite = true)
        if (!destination.isFile || destination.length() != source.length()) {
            destination.delete()
            return null
        }
        return AgentFileReference(
            displayName = safeName,
            absolutePath = destination.absolutePath,
            kind = AgentFileReferenceKind.File,
        )
    }

    private fun conversationDir(conversationId: String): File? {
        val directory = File(root, sanitize(conversationId)).apply { mkdirs() }
        return directory.takeIf { it.isDirectory }
    }

    fun copyConversation(fromId: String, toId: String) {
        if (fromId == toId) return
        val source = File(root, sanitize(fromId))
        if (!source.isDirectory) return
        val target = File(root, sanitize(toId))
        if (target.exists()) target.deleteRecursively()
        check(source.copyRecursively(target, overwrite = true)) { "分支附件复制失败" }
        if (Thread.currentThread().isInterrupted) throw InterruptedException("分支附件复制已取消")
    }

    fun rewriteCachedPath(value: String, fromId: String, toId: String): String {
        if (value.isEmpty() || fromId == toId) return value
        val scheme = if (value.startsWith("file:///")) "file://" else ""
        val raw = value.removePrefix(scheme)
        if (!raw.startsWith('/') || raw.any { it.isISOControl() } || '\\' in raw) return value
        // Do not normalize traversal into ownership of another conversation (or accept prose).
        if (raw.split('/').any { it == "." || it == ".." }) return value
        val source = File(root, sanitize(fromId))
        val target = File(root, sanitize(toId))
        val bases = mutableListOf(source.absolutePath)
        // Only this application's data-root alias, not arbitrary matching cache-directory segments.
        val rootSuffix = "/$packageName/cache/$CACHE_DIRECTORY"
        if (root.absolutePath.endsWith(rootSuffix)) {
            val dataRoot = root.absolutePath.removeSuffix(rootSuffix)
            // /data/data is user 0's alias, never another Android profile's cache.
            if (dataRoot == "/data/user/0" || dataRoot == "/data/data") {
                bases += "/data/data$rootSuffix/${sanitize(fromId)}"
                bases += "/data/user/0$rootSuffix/${sanitize(fromId)}"
            }
        }
        val base = bases.firstOrNull { raw.startsWith("$it/") } ?: return value
        val relative = raw.removePrefix("$base/")
        if (relative.isEmpty() || relative.split('/').any { it.isEmpty() }) return value
        return runCatching {
            // Also reject existing symlink escapes. No file must exist for a valid historical slot.
            if (source.canonicalFile != File(root.canonicalFile, sanitize(fromId)) ||
                target.canonicalFile != File(root.canonicalFile, sanitize(toId)) ||
                !File(source, relative).canonicalPath.startsWith(source.canonicalPath + "/") ||
                !File(target, relative).canonicalPath.startsWith(target.canonicalPath + "/")) value
            else scheme + File(target, relative).absolutePath
        }.getOrDefault(value)
    }

    fun deleteConversation(conversationId: String) {
        val directory = File(root, sanitize(conversationId))
        if (directory.exists()) directory.deleteRecursively()
        pruneEmptyRoot()
    }

    fun deleteOrphans(activeConversationIds: Set<String>) {
        if (!root.isDirectory) return
        val active = activeConversationIds.mapTo(mutableSetOf(), ::sanitize)
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in active) {
                child.deleteRecursively()
            }
        }
        pruneEmptyRoot()
    }

    private fun pruneEmptyRoot() {
        if (root.isDirectory && root.list().isNullOrEmpty()) root.delete()
    }

    /**
     * 模型侧常见的 /home/workdir/attachments/image.jpg 并不是 Android 路径。
     * 别名只在当前会话自己的附件目录里解析：跨会话按修改时间挑“最新”的图，
     * 会把别的会话的截图当成用户刚发的附件。没有会话或当前会话没有附件时返回 null。
     */
    fun resolveReadableFile(raw: String, conversationId: String): File? {
        val path = raw.trim().removePrefix("file://")
        if (path.isEmpty() || path.startsWith("content://") || path.contains('\u0000')) return null
        val direct = File(path)
        if (isOtherConversationAttachment(path, conversationId)) return null
        if (direct.isFile && direct.canRead()) return direct
        if (!isAttachmentAlias(path)) return null
        val requested = AgentFileReferenceGateway.safeImportName(direct.name)
        if (requested.isBlank() || requested.contains("..")) return null
        val directory = conversationAttachmentDir(conversationId) ?: return null
        return directory.listFiles().orEmpty()
            .asSequence()
            .filter { it.isFile && it.canRead() && matchesAttachmentName(it.name, requested) }
            .maxByOrNull { it.lastModified() }
    }

    /**
     * 路径是否落在别的会话的附件目录里。聊天附件只属于它所在的会话，模型读别的会话的
     * 附件会把旧截图当成用户刚发的图；缓存目录外的普通文件不受限制。
     * 语音浮窗、Breeno 等不属于聊天会话的 run 没有会话 ID，不做归属判断（它们只有别名被禁用）。
     */
    fun isOtherConversationAttachment(path: String, conversationId: String): Boolean {
        if (conversationId.isBlank()) return false
        val owner = attachmentOwner(path) ?: return false
        return owner != sanitize(conversationId)
    }

    /** 缓存根下的会话子目录名；不在缓存目录里（含 /data/data 与 /data/user/0 两种写法）时返回 null。 */
    private fun attachmentOwner(path: String): String? {
        val normalized = path.trim().removePrefix("file://").replace('\\', '/')
        val canonical = runCatching { File(normalized).canonicalPath }.getOrDefault(normalized)
        val roots = listOfNotNull(
            runCatching { root.canonicalPath }.getOrNull(),
            root.absolutePath,
        ).distinct()
        for (candidate in listOf(canonical, normalized)) {
            for (base in roots) {
                if (!candidate.startsWith("$base/")) continue
                return candidate.removePrefix("$base/").substringBefore('/').takeIf { it.isNotBlank() }
            }
        }
        // 同一缓存文件常以另一种应用数据根出现（/data/data 与 /data/user/0）。
        // 只认本应用 cache 目录下的这一段，工作区里恰好同名的目录不算。
        val segment = "/$packageName/cache/$CACHE_DIRECTORY/"
        val index = normalized.indexOf(segment)
        if (index < 0 || !AppDataRoot.matches(normalized.substring(0, index))) return null
        return normalized.substring(index + segment.length).substringBefore('/').takeIf { it.isNotBlank() }
    }

    /** 已存在的当前会话附件目录；不创建目录，会话 ID 为空时不回退到其它会话。 */
    private fun conversationAttachmentDir(conversationId: String): File? {
        if (conversationId.isBlank()) return null
        return File(root, sanitize(conversationId)).takeIf { it.isDirectory }
    }

    private fun isAttachmentAlias(path: String): Boolean {
        val normalized = path.replace('\\', '/').trim()
        return normalized.startsWith("/home/workdir/attachments/") ||
            normalized == "/home/workdir/attachments" ||
            normalized.startsWith("/workspace/attachments/")
    }

    private fun matchesAttachmentName(fileName: String, requested: String): Boolean {
        if (fileName == requested) return true
        if (fileName.endsWith("-$requested")) return true
        // 通用名 image.jpg 不知道是第几张，只能取当前会话最新的一张聊天图片。
        val genericImage = requested.matches(Regex("(?i)image\\.(jpg|jpeg|png|webp|gif)"))
        return genericImage && ChatImageFileName.matches(fileName)
    }

    companion object {
        const val CACHE_DIRECTORY = "eta-chat-images"
        private val ChatImageFileName = Regex(".*-chat-image-\\d+\\.[A-Za-z0-9]+")
        private val AppDataRoot = Regex("/data/data|/data/user/\\d+")

        fun decodeImageBytes(value: String): ByteArray? {
            val trimmed = value.trim()
            if (!trimmed.startsWith("data:image/", ignoreCase = true)) return null
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            return runCatching {
                Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            }.getOrNull()
        }

        fun readBytes(value: String): ByteArray? {
            decodeImageBytes(value)?.let { return it }
            val path = value.trim().removePrefix("file://")
            if (!path.startsWith("/")) return null
            val file = File(path)
            if (!file.isFile || file.length() !in 1L..MAX_AGENT_IMAGE_BYTES.toLong()) return null
            return runCatching { file.readBytes() }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_AGENT_IMAGE_BYTES }
        }

        private fun sanitize(conversationId: String): String =
            AgentFileReferenceGateway.safeImportName(conversationId)
    }
}
