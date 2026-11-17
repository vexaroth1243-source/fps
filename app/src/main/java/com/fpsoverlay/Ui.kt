package com.fpsoverlay

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0F1012)
private val CardBg = Color(0xFF1A1B1E)
private val Sub = Color(0xFF9AA0A6)
private val MutedC = Color(0xFF6B7076)
private val GreenC = Color(0xFF2EA043)
private val RedC = Color(0xFFDA3633)
private val BlueC = Color(0xFF2F81F7)
private val GrayC = Color(0xFF6E7681)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = BlueC, background = Bg, surface = CardBg, onSurface = Color.White
        ),
        content = content
    )
}

private fun deviceName(): String =
    if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL.isNotBlank() && Build.SOC_MODEL != Build.UNKNOWN)
        "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
    else Build.HARDWARE

@Composable
fun App(sampler: Sampler, s: Settings) {
    val snap by sampler.snap.collectAsState()
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().background(Bg)) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp, 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("FPS Overlay", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(10.dp))
            Text("${Build.MANUFACTURER} ${Build.MODEL}", color = Sub, fontSize = 12.sp)
        }
        TabRow(selectedTabIndex = tab, containerColor = CardBg) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("İzleme") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Katman") })
        }
        if (tab == 0) MonitorTab(snap, s, sampler) else OverlayTab(snap, s, sampler)
    }
}

// ---------------- İzleme sekmesi ----------------
@Composable
fun MonitorTab(snap: Snap, s: Settings, sampler: Sampler) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for ((g, title) in Metrics.groups) {
            val sub = when (g) {
                Metrics.GFps -> when {
                    !snap.fpsAvailable -> snap.fpsNote ?: "FPS ölçümü kullanılamıyor"
                    snap.fpsProcess != null -> "Ölçülen oyun: ${snap.fpsProcess}"
                    else -> "Çalışan oyun bekleniyor"
                }
                Metrics.GCpu -> deviceName()
                Metrics.GRam -> snap.ramTotalGb?.let { Fmt.n(it, 1) + " GB" } ?: ""
                else -> ""
            }
            item(key = "g_$g") {
                GroupHeader(
                    title, sub,
                    checked = Metrics.inGroup(g).any { s.isOn(it.key) },
                    onChange = { on -> Metrics.inGroup(g).forEach { s.setOn(it.key, on) } }
                )
            }
            items(Metrics.inGroup(g), key = { it.key }) { def ->
                MetricCard(
                    def = def,
                    value = snap.values[def.key],
                    hist = snap.history[def.key].orEmpty(),
                    max = sampler.maxFor(def, snap),
                    on = s.isOn(def.key),
                    onToggle = { s.setOn(def.key, !s.isOn(def.key)) }
                )
            }
        }
    }
}

@Composable
fun GroupHeader(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).background(CardBg).padding(16.dp, 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (sub.isNotEmpty()) Text(sub, color = Sub, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun MetricCard(
    def: MetricDef,
    value: Double?,
    hist: List<Double?>,
    max: Double,
    on: Boolean,
    onToggle: () -> Unit
) {
    val accent = Color(def.accent)
    val m = if (max > 0) max else 1.0

    Column(Modifier.fillMaxWidth().background(CardBg).padding(14.dp, 12.dp, 14.dp, 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(46.dp)) {
                val stroke = 5.dp.toPx()
                val sz = Size(size.width - stroke, size.height - stroke)
                val tl = Offset(stroke / 2, stroke / 2)
                drawArc(
                    color = Color(0x22FFFFFF), startAngle = 135f, sweepAngle = 270f,
                    useCenter = false, topLeft = tl, size = sz,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                if (value != null) {
                    val frac = (value / m).coerceIn(0.0, 1.0).toFloat()
                    if (frac > 0.003f) drawArc(
                        color = accent, startAngle = 135f, sweepAngle = 270f * frac,
                        useCenter = false, topLeft = tl, size = sz,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(def.title, color = Sub, fontSize = 12.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    // Sensör okunamıyorsa sahte değer gösterme
                    Text(
                        value?.let { Fmt.n(it, def.decimals) } ?: "Yok",
                        fontSize = 28.sp, fontWeight = FontWeight.Bold,
                        color = if (value != null) Color.White else MutedC
                    )
                    if (value != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(def.unit, color = Sub, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
            }
            Box(
                Modifier.size(40.dp).clickable { onToggle() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (on) "✓" else "+",
                    color = if (on) accent else Sub,
                    fontSize = 22.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(52.dp)) {
            val w = size.width
            val h = size.height
            if (hist.isEmpty()) return@Canvas
            val step = w / (Sampler.HistoryLen - 1)
            val off = Sampler.HistoryLen - hist.size   // en yeni örnek sağda
            fun y(v: Double): Float = h - (v / m).coerceIn(0.0, 1.0).toFloat() * (h - 2f)

            var i = 0
            while (i < hist.size) {
                val first = hist[i]
                if (first == null) { i++; continue }   // boşluklar çizilmez

                val area = Path()
                val line = Path()
                val x0 = (off + i) * step
                area.moveTo(x0, h)
                line.moveTo(x0, y(first))
                var lastX = x0
                var j = i
                while (j < hist.size) {
                    val v = hist[j] ?: break
                    val x = (off + j) * step
                    val yy = y(v)
                    area.lineTo(x, yy)
                    if (j > i) line.lineTo(x, yy)
                    lastX = x
                    j++
                }
                area.lineTo(lastX, h)
                area.close()
                drawPath(area, accent.copy(alpha = 0.78f))
                drawPath(line, accent, style = Stroke(2.dp.toPx()))
                i = j
            }
        }
    }
}

// ---------------- Katman sekmesi ----------------
@Composable
fun OverlayTab(snap: Snap, s: Settings, sampler: Sampler) {
    val ctx = LocalContext.current
    val running by OverlayService.running.collectAsState()
    var logNote by remember { mutableStateOf("") }
    var reportNote by remember { mutableStateOf("") }
    var permNote by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Başlat / durdur
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(if (running) GreenC else GrayC, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(if (running) "Gösterge çalışıyor" else "Gösterge kapalı", color = Sub)
        }
        Button(
            onClick = {
                if (running) {
                    OverlayService.stop(ctx)
                } else if (!OverlayService.canDraw(ctx)) {
                    permNote = "Açılan ekranda FPS Overlay için 'Diğer uygulamaların üzerinde göster' iznini ver, sonra geri dön."
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}"))
                    )
                } else {
                    permNote = ""
                    OverlayService.start(ctx)
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (running) RedC else GreenC)
        ) { Text(if (running) "■  Göstergeyi durdur" else "▶  Göstergeyi başlat", fontSize = 15.sp) }
        if (permNote.isNotEmpty()) Text(permNote, color = Sub, fontSize = 12.sp)

        // Konum
        Section("Konum")
        val codes = listOf(listOf("TL", "TC", "TR"), listOf("ML", "MC", "MR"), listOf("BL", "BC", "BR"))
        for (row in codes) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                for (c in row) RadioButton(selected = s.position == c, onClick = { s.position = c })
            }
        }
        Text("Göstergeyi parmakla sürükleyerek de taşıyabilirsin.", color = Sub, fontSize = 12.sp)

        // Görünüm
        Section("Görünüm")
        LabeledSlider("Saydamlık", "${(s.opacity * 100).toInt()}%", s.opacity, 0.2f..1f) { s.opacity = it }
        LabeledSlider("Boyut", "${(s.scale * 100).toInt()}%", s.scale, 0.6f..2f) { s.scale = it }
        SwitchRow("Tıklamayı geçir", "Açıkken gösterge dokunuşları engellemez. Geri almak için bildirimdeki düğmeyi kullan.", s.clickThrough) {
            s.clickThrough = it
        }

        // Örnekleme
        Section("Örnekleme aralıkları")
        LabeledSlider("FPS", Fmt.n(s.fpsIntervalSec.toDouble(), 2) + " sn", s.fpsIntervalSec, 0.25f..2f) { s.fpsIntervalSec = it }
        LabeledSlider("Donanım", Fmt.n(s.hwIntervalSec.toDouble(), 1) + " sn", s.hwIntervalSec, 0.5f..5f) { s.hwIntervalSec = it }

        // Root
        Section("Gelişmiş")
        SwitchRow(
            "Root kullan",
            "Oyunun FPS'ini ölçmek ve okunamayan sensör dosyalarını açmak için gerekir. Root yoksa FPS 'yok' görünür.",
            s.useRoot
        ) { s.useRoot = it }

        // Kayıt ve rapor
        Section("Kayıt ve rapor")
        Button(
            onClick = {
                logNote = try {
                    if (snap.logging) "Kayıt durduruldu. Dosya: ${sampler.logPath}".also { sampler.stopLogging() }
                    else "Kaydediliyor: " + sampler.startLogging()
                } catch (e: Exception) { "Kayıt başlatılamadı: ${e.message}" }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = if (snap.logging) RedC else BlueC)
        ) { Text(if (snap.logging) "Kaydı durdur" else "Günlük kaydını başlat") }
        if (logNote.isNotEmpty()) Text(logNote, color = Sub, fontSize = 12.sp)

        Button(
            onClick = {
                reportNote = try { "Kaydedildi: " + sampler.saveReport() }
                catch (e: Exception) { "Rapor kaydedilemedi: ${e.message}" }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = CardBg)
        ) { Text("Sensör raporunu kaydet") }
        if (reportNote.isNotEmpty()) Text(reportNote, color = Sub, fontSize = 12.sp)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun LabeledSlider(label: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Sub, fontSize = 13.sp)
            Text(valueText, fontSize = 13.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 14.sp)
            Text(note, color = Sub, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
