package com.fpsoverlay

import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Dosya okuma ve (isteğe bağlı) root komutu yardımcıları. */
object Sys {
    fun readDirect(path: String): String? = try {
        File(path).inputStream().bufferedReader().use { it.readText() }.trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    private var rootState: Boolean? = null
    private var rootCheckedAt = 0L

    @Synchronized
    fun hasRoot(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (rootState == true) return true
        if (rootState == false && now - rootCheckedAt < 30_000) return false
        val ok = su("id", 8000)?.contains("uid=0") == true
        rootState = ok
        rootCheckedAt = now
        return ok
    }

    fun su(cmd: String, timeoutMs: Long = 3000): String? {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuilder()
            val reader = thread(isDaemon = true) {
                try { sb.append(p.inputStream.bufferedReader().readText()) } catch (_: Exception) {}
            }
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroy()
                return null
            }
            reader.join(500)
            sb.toString()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Yolları önce doğrudan okur. Okunamayanları, useRoot açıksa ve root varsa
     * tek bir su çağrısıyla toplu okur.
     */
    fun readMany(paths: List<String>, useRoot: Boolean): Map<String, String> {
        val out = HashMap<String, String>()
        val missing = ArrayList<String>()
        for (p in paths) {
            val t = readDirect(p)
            if (t != null) out[p] = t else missing.add(p)
        }
        if (useRoot && missing.isNotEmpty() && hasRoot()) {
            val script = missing.joinToString("; ") { "echo '@@$it'; cat $it 2>/dev/null" }
            val res = su(script, 4000)
            if (res != null) {
                var cur: String? = null
                val sb = StringBuilder()
                fun flush() {
                    val c = cur
                    if (c != null) {
                        val t = sb.toString().trim()
                        if (t.isNotEmpty()) out[c] = t
                    }
                    sb.setLength(0)
                }
                for (line in res.lineSequence()) {
                    if (line.startsWith("@@")) {
                        flush()
                        cur = line.substring(2).trim()
                    } else {
                        sb.append(line).append('\n')
                    }
                }
                flush()
            }
        }
        return out
    }
}
