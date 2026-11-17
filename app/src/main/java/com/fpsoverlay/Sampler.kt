package com.fpsoverlay

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

data class Snap(
    val values: Map<String, Double?>,
    val history: Map<String, List<Double?>>,
    val ramTotalGb: Double?,
    val fpsProcess: String?,
    val fpsAvailable: Boolean,
    val fpsNote: String?,
    val logging: Boolean
)

/**
 * Tüm ölçümlerin tek kaynağı. Ekran ve gösterge (OverlayService) buradan okur.
 * acquire/release ile sayılır; kullanan kalmayınca zamanlayıcılar durur.
 */
class Sampler private constructor(private val ctx: Context) {
    companion object {
        const val HistoryLen = 90
        @Volatile private var inst: Sampler? = null
        fun get(ctx: Context): Sampler = inst ?: synchronized(this) {
            inst ?: Sampler(ctx.applicationContext).also { inst = it }
        }
    }

    private val s = Settings.get(ctx)
    private val hw = HwSource(ctx, s)
    private val fps = FpsSource(ctx, s)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val hist = HashMap<String, ArrayList<Double?>>()
    private val latest = HashMap<String, Double?>()
    private var users = 0
    private var jobs: List<Job> = emptyList()
    private var ramTotal: Double? = null
    private var fpsProcess: String? = null
    private var log: BufferedWriter? = null
    var logPath: String? = null
        private set

    init {
        for (m in Metrics.all) { hist[m.key] = ArrayList(); latest[m.key] = null }
    }

    private val _snap = MutableStateFlow(build())
    val snap: StateFlow<Snap> = _snap

    fun acquire() {
        synchronized(lock) {
            users++
            if (users == 1) start()
        }
    }

    fun release() {
        synchronized(lock) {
            if (users > 0) users--
            if (users == 0) stop()
        }
    }

    private fun start() {
        jobs = listOf(
            scope.launch {
                while (isActive) {
                    try {
                        val r = fps.sample()
                        synchronized(lock) {
                            push("fps", r?.fps)
                            push("frametime", r?.frameMs)
                            push("fps1", r?.low1Fps)
                            fpsProcess = r?.process
                        }
                        publish()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { }
                    delay((s.fpsIntervalSec * 1000).toLong().coerceIn(250, 2000))
                }
            },
            scope.launch {
                while (isActive) {
                    try {
                        val h = hw.read()
                        synchronized(lock) {
                            ramTotal = h.ramTotalGb
                            push("cpu_load", h.cpuLoad)
                            push("cpu_temp", h.cpuTemp)
                            push("cpu_clock", h.cpuClockGhz)
                            push("gpu_load", h.gpuLoad)
                            push("gpu_temp", h.gpuTemp)
                            push("ram_used", h.ramUsedGb)
                            push("bat_temp", h.batTemp)
                            push("bat_level", h.batLevel)
                            writeLog()
                        }
                        publish()
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { }
                    delay((s.hwIntervalSec * 1000).toLong().coerceIn(500, 5000))
                }
            }
        )
    }

    private fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }

    private fun push(key: String, v: Double?) {
        val l = hist[key] ?: return
        l.add(v)
        if (l.size > HistoryLen) l.removeAt(0)
        latest[key] = v
    }

    private fun build(): Snap = synchronized(lock) {
        Snap(
            values = HashMap(latest),
            history = hist.mapValues { it.value.toList() },
            ramTotalGb = ramTotal,
            fpsProcess = fpsProcess,
            fpsAvailable = fps.available,
            fpsNote = fps.note,
            logging = log != null
        )
    }

    private fun publish() { _snap.value = build() }

    /** Halka ve grafik için üst sınır. */
    fun maxFor(def: MetricDef, snap: Snap): Double {
        if (def.max > 0) return def.max
        val total = snap.ramTotalGb
        if (def.key == "ram_used" && total != null && total > 0) return total
        val peak = snap.history[def.key].orEmpty().filterNotNull().maxOrNull() ?: 0.0
        val floor = when (def.key) {
            "fps", "fps1" -> 60.0
            "frametime" -> 16.7
            "cpu_clock" -> 4.0
            else -> 1.0
        }
        return max(floor, peak * 1.2)
    }

    // --- CSV kaydı ---
    private fun outDir(sub: String? = null): File {
        val base = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val d = if (sub != null) File(base, sub) else base
        d.mkdirs()
        return d
    }

    fun startLogging(): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val f = File(outDir("Kayitlar"), "kayit_$stamp.csv")
        val w = f.bufferedWriter(Charsets.UTF_8)
        w.write("zaman;" + Metrics.all.joinToString(";") { "${it.key} (${it.unit})" })
        w.newLine()
        w.flush()
        synchronized(lock) { log = w; logPath = f.absolutePath }
        acquire()   // kayıt sürerken ölçüm durmasın
        publish()
        return f.absolutePath
    }

    fun stopLogging() {
        val had = synchronized(lock) {
            val h = log != null
            try { log?.close() } catch (_: Exception) {}
            log = null
            h
        }
        if (had) release()
        publish()
    }

    private fun writeLog() {
        val w = log ?: return
        try {
            val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val cells = Metrics.all.joinToString(";") { m ->
                latest[m.key]?.let { String.format(Fmt.tr, "%.3f", it) } ?: ""
            }
            w.write("$t;$cells")
            w.newLine()
            w.flush()
        } catch (e: Exception) {
            try { w.close() } catch (_: Exception) {}
            log = null
        }
    }

    // --- Doğrulama raporu ---
    fun saveReport(): String {
        val sb = StringBuilder()
        sb.appendLine("FPS ölçümü: " + if (fps.available) "çalışıyor" else "kullanılamıyor: ${fps.note}")
        sb.appendLine("Ölçülen uygulama: ${fpsProcess ?: "yok"}")
        sb.appendLine()
        sb.append(hw.report())
        val f = File(outDir(), "sensor-raporu.txt")
        f.writeText(sb.toString(), Charsets.UTF_8)
        return f.absolutePath
    }
}
