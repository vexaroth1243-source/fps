package com.fpsoverlay

import android.content.Context
import android.os.SystemClock

data class FpsResult(val fps: Double, val frameMs: Double, val low1Fps: Double?, val process: String)

/**
 * Önde çalışan uygulamanın (oyunun) FPS'i. Android, başka uygulamanın karelerini
 * normal izinle okutmaz; bu yüzden SurfaceFlinger'ın kare zaman damgaları root ile alınır:
 *   dumpsys SurfaceFlinger --latency <katman>
 * Root yoksa FPS "yok" olarak kalır, sahte değer üretilmez.
 */
class FpsSource(private val ctx: Context, private val s: Settings) {
    var available = false
        private set
    var note: String? = null
        private set

    private var pkg: String? = null
    private var layer: String? = null
    private var layerAt = 0L
    private var label: String? = null

    private val times = ArrayList<Double>()   // ms, CLOCK_MONOTONIC
    private var lastTs = 0.0

    private val focusRx = Regex("\\s([A-Za-z0-9_.]+)/[A-Za-z0-9_.$]+\\}")

    fun sample(): FpsResult? {
        if (!s.useRoot) {
            available = false
            note = "FPS ölçümü için root gerekli (Katman sekmesinden 'Root kullan')"
            return null
        }
        if (!Sys.hasRoot()) {
            available = false
            note = "Root erişimi alınamadı"
            return null
        }
        available = true
        note = null

        val now = SystemClock.elapsedRealtime()
        if (layer == null || now - layerAt > 3000) resolveLayer(now)
        val l = layer ?: return null
        val p = pkg ?: return null

        val out = Sys.su("dumpsys SurfaceFlinger --latency '$l'", 2500)
        if (out == null) { layer = null; return null }

        val lines = out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size < 2) { layer = null; return null }

        val fresh = ArrayList<Double>()
        for (ln in lines.drop(1)) {
            val parts = ln.split(Regex("\\s+"))
            if (parts.size < 3) continue
            val t = parts[1].toLongOrNull() ?: continue
            if (t <= 0L || t == Long.MAX_VALUE) continue   // geçersiz kare
            val ms = t / 1_000_000.0
            if (ms > lastTs) fresh.add(ms)
        }
        fresh.sort()
        for (ms in fresh) { times.add(ms); lastTs = ms }
        if (times.isEmpty()) return null

        val keepFrom = lastTs - 10_000
        while (times.isNotEmpty() && times[0] < keepFrom) times.removeAt(0)

        // Bir süredir kare gelmiyorsa akış bayat: değer gösterme
        val nowMs = System.nanoTime() / 1_000_000.0
        if (nowMs - lastTs > 2500) return null

        val win = times.filter { it > lastTs - 1000 }
        if (win.size < 2) return null
        val span = win.last() - win.first()
        if (span <= 0) return null
        val fps = (win.size - 1) / span * 1000.0
        val frameMs = span / (win.size - 1)

        var low1: Double? = null
        if (times.size >= 20) {
            val deltas = times.zipWithNext { a, b -> b - a }.sorted()
            val p99 = deltas[((deltas.size - 1) * 0.99).toInt()]
            if (p99 > 0) low1 = 1000.0 / p99
        }
        return FpsResult(fps, frameMs, low1, label ?: p)
    }

    private fun resolveLayer(now: Long) {
        layerAt = now
        val out = Sys.su("dumpsys window | grep mCurrentFocus; echo @@LAYERS; dumpsys SurfaceFlinger --list", 4000)
        if (out == null) { layer = null; return }

        val idx = out.indexOf("@@LAYERS")
        val focus = if (idx >= 0) out.substring(0, idx) else out
        val layerText = if (idx >= 0) out.substring(idx + 8) else ""

        val p = focusRx.find(focus)?.groupValues?.get(1)
        if (p == null || p == ctx.packageName) {
            // Önde ölçülecek bir uygulama yok
            pkg = null; layer = null; label = null
            times.clear(); lastTs = 0.0
            return
        }
        if (p != pkg) {
            times.clear(); lastTs = 0.0
            pkg = p
            label = try {
                val ai = ctx.packageManager.getApplicationInfo(p, 0)
                ctx.packageManager.getApplicationLabel(ai).toString()
            } catch (e: Exception) { p }
        }

        val names = layerText.lineSequence().map { it.trim() }.filter { it.contains(p) }.toList()
        layer = names.firstOrNull { it.contains("SurfaceView") && !it.startsWith("Background") }
            ?: names.firstOrNull { !it.startsWith("Background") }
            ?: names.firstOrNull()
    }
}
