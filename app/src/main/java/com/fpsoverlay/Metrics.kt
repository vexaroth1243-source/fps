package com.fpsoverlay

import java.util.Locale

object Fmt {
    /** Ekranda Türkçe ondalık virgül kullanılır (65,8). */
    val tr: Locale = Locale("tr", "TR")
    fun n(v: Double, decimals: Int): String = String.format(tr, "%.${decimals}f", v)
}

/** max: halka/grafik üst sınırı. 0 ise veriye göre otomatik hesaplanır. */
data class MetricDef(
    val key: String,
    val group: String,
    val title: String,
    val unit: String,
    val decimals: Int,
    val max: Double,
    val accent: Long
)

object Metrics {
    const val GFps = "FPS"
    const val GCpu = "CPU"
    const val GGpu = "GPU"
    const val GRam = "RAM"
    const val GBat = "PIL"

    val groups = listOf(
        GFps to "FPS", GCpu to "CPU", GGpu to "GPU", GRam to "Sistem belleği", GBat to "Pil"
    )

    val all = listOf(
        MetricDef("fps", GFps, "Kare hızı", "FPS", 0, 0.0, 0xFF2DD4BF),
        MetricDef("frametime", GFps, "Çerçeve zamanı", "ms", 1, 0.0, 0xFF2DD4BF),
        MetricDef("fps1", GFps, "%1 düşük FPS", "FPS", 0, 0.0, 0xFF2DD4BF),

        MetricDef("cpu_load", GCpu, "Kullanım", "%", 0, 100.0, 0xFFF97316),
        MetricDef("cpu_temp", GCpu, "CPU sıcaklığı", "°C", 1, 100.0, 0xFFF97316),
        MetricDef("cpu_clock", GCpu, "CPU hızı", "GHz", 2, 0.0, 0xFFF97316),

        MetricDef("gpu_load", GGpu, "Kullanım", "%", 0, 100.0, 0xFFEF4444),
        MetricDef("gpu_temp", GGpu, "GPU sıcaklığı", "°C", 0, 110.0, 0xFFEF4444),

        MetricDef("ram_used", GRam, "Kullanım", "GB", 1, 0.0, 0xFFA855F7),

        MetricDef("bat_temp", GBat, "Pil sıcaklığı", "°C", 1, 60.0, 0xFFFACC15),
        MetricDef("bat_level", GBat, "Şarj", "%", 0, 100.0, 0xFFFACC15)
    )

    fun inGroup(group: String) = all.filter { it.group == group }
}
