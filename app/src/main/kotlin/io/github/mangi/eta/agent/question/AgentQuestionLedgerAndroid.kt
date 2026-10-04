package io.github.mangi.eta.agent.question

import android.content.Context
import android.util.AtomicFile
import java.io.File

/** Thin Android adapter; ledger policy and all JVM-testable logic stay in AgentQuestionLedger. */
internal object AgentQuestionLedgerAndroid {
    fun forContext(context: Context): AgentQuestionLedger {
        val base = File(context.applicationContext.filesDir, "runtime-question-ledger.json")
        return AgentQuestionLedger(object : AgentQuestionLedger.Storage {
            override val key: String = base.absolutePath
            override fun read(): String? {
                if (!base.exists() && !File(base.path + ".bak").exists()) return null
                AtomicFile(base).openRead().use { input ->
                    val bytes = input.readBytesBounded()
                    return bytes.toString(Charsets.UTF_8)
                }
            }
            override fun write(json: String) {
                base.parentFile?.mkdirs()
                val atomic = AtomicFile(base)
                val output = atomic.startWrite()
                try {
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    output.write(bytes)
                    output.fd.sync() // AtomicFile.finishWrite logs some failures rather than throwing.
                    atomic.finishWrite(output)
                    check(base.exists() && !File(base.path + ".new").exists())
                    val persisted = atomic.openRead().use { it.readBytesBounded() }
                    check(bytes.contentEquals(persisted)) { "Question ledger commit was not confirmed" }
                } catch (failure: Throwable) {
                    runCatching { atomic.failWrite(output) }
                    throw failure
                }
            }
        })
    }

    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            require(output.size() + count <= AgentQuestionLedger.MAX_BYTES)
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
