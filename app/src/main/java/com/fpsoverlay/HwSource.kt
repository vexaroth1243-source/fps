package com.fpsoverlay

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlin.math.abs

data class HwSnap(
    val cpuLoad: Double?,
    val cpuTemp: Double?,
    val cpuClockGhz: Double?,
    val gpuLoad: Double?,
    val gpuTemp: Double?,
    val ramUsedGb: Double?,
    val ramTotalGb: Double?,
    val batTemp: Double?,
    val batLevel: Double?
)

/**
 * CPU/GPU/RAM/pil okuma. Okunamayan her değer null döner (ekranda "Yok").
 * Sensör dosyaları cihaza ve Android sürümüne göre değişir; erişilemeyenler
 * root açıksa su ile denenir.
 */
class HwSource(private val ctx: Context, private val s: Settings) {
    private val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val cores = Runtime.getRuntime().availableProcessors()

    private var prevIdle = -1L
    private var prevTotal = -1L

    private var zoneTypes: Map<Int, String>? = null
    private var cpuZones: List<Int> = emptyList()
    private var gpuZones: List<Int> = emptyList()
    private var discoveredWithRoot: Boolean? = null
    private var discoveredAt = 0L

    private var gpuHit: String? = null
    private var lastData: Map<String, String> = emptyMap()

    private val cpuRx = Regex("(?i)(cpu|soc_thermal|mtktscpu|big|little|mid)")
    private val gpuLoadPaths = listOf(
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/devices/platform/mali/utilization"
    )

    private fun freqPath(i: Int) = "/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq"
    private fun tempPath(i: Int) = "/sys/class/thermal/thermal_zone$i/temp"
    private fun typePath(i: Int) = "/sys/class/thermal/thermal_zone$i/type"

    private fun ensureZones() {
        val now = SystemClock.elapsedRealtime()
        val zt = zoneTypes
        val need = zt == null ||
            discoveredWithRoot != s.useRoot ||
            (zt.isEmpty() && now - discoveredAt > 30_000)
        if (!need) return

        val d = Sys.readMany((0 until 80).map { typePath(it) }, s.useRoot)
        val m = HashMap<Int, String>()
        for (i in 0 until 80) d[typePath(i)]?.let { m[i] = it }
        zoneTypes = m
        cpuZones = m.filter { (_, t) -> cpuRx.containsMatchIn(t) && !t.contains("gpu", true) }.keys.sorted()
        gpuZones = m.filter { (_, t) -> t.contains("gpu", true) }.keys.sorted()
        discoveredWithRoot = s.useRoot
        discoveredAt = now
    }

    fun read(): HwSnap {
        ensureZones()

        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val gb = 1024.0 * 1024.0 * 1024.0
        val ramTotal = mi.totalMem / gb
        val ramUsed = (mi.totalMem - mi.availMem) / gb

        val paths = ArrayList<String>()
        paths.add("/proc/stat")
        for (i in 0 until cores) paths.add(freqPath(i))
        cpuZones.forEach { paths.add(tempPath(it)) }
        gpuZones.forEach { paths.add(tempPath(it)) }
        val hit = gpuHit
        paths.addAll(if (hit != null) listOf(hit) else gpuLoadPaths)

        val d = Sys.readMany(paths, s.useRoot)
        lastData = d

        // CPU hızı: okunabilen çekirdeklerin ortalaması (kHz -> GHz)
        val freqs = (0 until cores).mapNotNull { d[freqPath(it)]?.trim()?.toDoubleOrNull() }
        val clock = if (freqs.isEmpty()) null else freqs.average() / 1_000_000.0

        // CPU sıcaklığı: en sıcak CPU bölgesi
        val cpuTemp = cpuZones.mapNotNull { parseTemp(d[tempPath(it)]) }.maxOrNull()
        val gpuTemp = gpuZones.mapNotNull { parseTemp(d[tempPath(it)]) }.maxOrNull()

        var gpuLoad: Double? = null
        for (p in (if (hit != null) listOf(hit) else gpuLoadPaths)) {
            val txt = d[p] ?: continue
            val v = parseGpu(p, txt)
            if (v != null) { gpuLoad = v; gpuHit = p; break }
        }

        val bat = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        var batTemp: Double? = null
        var batLevel: Double? = null
        if (bat != null) {
            val t = bat.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (t != Int.MIN_VALUE) batTemp = t / 10.0
            val l = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val sc = bat.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && sc > 0) batLevel = l * 100.0 / sc
        }

        return HwSnap(
            cpuLoad = cpuLoad(d["/proc/stat"]),
            cpuTemp = cpuTemp,
            cpuClockGhz = clock,
            gpuLoad = gpuLoad,
            gpuTemp = gpuTemp,
            ramUsedGb = ramUsed,
            ramTotalGb = ramTotal,
            batTemp = batTemp,
            batLevel = batLevel
        )
    }

    private fun cpuLoad(stat: String?): Double? {
        val line = stat?.lineSequence()?.firstOrNull { it.startsWith("cpu ") } ?: return null
        val t = line.trim().split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (t.size < 5) return null
        val idle = t[3] + t[4]
        val total = t.take(8).sum()
        val r = if (prevTotal >= 0 && total > prevTotal)
            (1.0 - (idle - prevIdle).toDouble() / (total - prevTotal)).coerceIn(0.0, 1.0) * 100.0
        else null
        prevIdle = idle
        prevTotal = total
        return r
    }

    private fun parseTemp(txt: String?): Double? {
        val v = txt?.trim()?.toDoubleOrNull() ?: return null
        val c = if (abs(v) >= 1000) v / 1000.0 else v
        return if (c in 10.0..130.0) c else null
    }

    private fun parseGpu(path: String, txt: String): Double? {
        val nums = Regex("\\d+(?:\\.\\d+)?").findAll(txt).mapNotNull { it.value.toDoubleOrNull() }.toList()
        if (nums.isEmpty()) return null
        val v = if (path.endsWith("gpubusy")) {
            if (nums.size >= 2 && nums[1] > 0) nums[0] / nums[1] * 100.0 else return null
        } else nums[0]
        return if (v in 0.0..100.0) v else null
    }

    /** Ham sensör dökümü: hangi dosya okunabildi, hangisi okunamadı. */
    fun report(): String {
        ensureZones()
        read()
        val d = lastData
        val sb = StringBuilder()
        sb.appendLine("Root: " + when {
            !s.useRoot -> "kapalı"
            Sys.hasRoot() -> "açık, erişim var"
            else -> "açık, erişim yok"
        })
        sb.appendLine("/proc/stat: " + if (d["/proc/stat"] != null) "okunabildi" else "okunamadı")
        val nf = (0 until cores).count { d[freqPath(it)] != null }
        sb.appendLine("CPU frekansı: $nf/$cores çekirdek okunabildi")
        sb.appendLine()
        sb.appendLine("Termal bölgeler (CPU olarak seçilenler: $cpuZones, GPU olarak seçilenler: $gpuZones):")
        val zt = zoneTypes.orEmpty()
        if (zt.isEmpty()) sb.appendLine("  hiçbiri okunamadı")
        val temps = Sys.readMany(zt.keys.map { tempPath(it) }, s.useRoot)
        for ((i, t) in zt.toSortedMap()) {
            val v = temps[tempPath(i)]
            sb.appendLine("  zone$i  $t = ${v ?: "okunamadı"}")
        }
        sb.appendLine()
        sb.appendLine("GPU yük dosyaları:")
        val vals = Sys.readMany(gpuLoadPaths, s.useRoot)
        for (p in gpuLoadPaths) sb.appendLine("  $p = ${vals[p] ?: "yok / okunamadı"}")
        return sb.toString()
    }
}
