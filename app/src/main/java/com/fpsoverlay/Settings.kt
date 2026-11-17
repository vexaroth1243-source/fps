package com.fpsoverlay

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf

/**
 * Kullanıcı ayarları (SharedPreferences). Her yazma bir sürüm sayacını artırır;
 * Compose bu sayacı okuyan getter'lar sayesinde otomatik yeniden çizilir.
 */
class Settings private constructor(ctx: Context) {
    companion object {
        @Volatile private var inst: Settings? = null
        fun get(ctx: Context): Settings = inst ?: synchronized(this) {
            inst ?: Settings(ctx.applicationContext).also { inst = it }
        }

        private val DEFAULT_ON = setOf(
            "fps", "cpu_load", "cpu_temp", "gpu_load", "gpu_temp", "ram_used"
        )
    }

    val prefs: SharedPreferences = ctx.getSharedPreferences("fpsoverlay", Context.MODE_PRIVATE)
    private val rev = mutableIntStateOf(0)
    private fun touch() { rev.intValue = rev.intValue + 1 }

    private fun overlaySet(): Set<String> =
        prefs.getStringSet("overlay", null)?.toSet() ?: DEFAULT_ON

    fun isOn(key: String): Boolean { rev.intValue; return key in overlaySet() }

    fun setOn(key: String, on: Boolean) {
        val set = overlaySet().toMutableSet()
        if (on) set.add(key) else set.remove(key)
        prefs.edit().putStringSet("overlay", set).apply()
        touch()
    }

    var fpsIntervalSec: Float
        get() { rev.intValue; return prefs.getFloat("fpsInterval", 0.5f) }
        set(v) { prefs.edit().putFloat("fpsInterval", v).apply(); touch() }

    var hwIntervalSec: Float
        get() { rev.intValue; return prefs.getFloat("hwInterval", 1.0f) }
        set(v) { prefs.edit().putFloat("hwInterval", v).apply(); touch() }

    /** TL TC TR / ML MC MR / BL BC BR veya "custom" (elle sürüklenmiş). */
    var position: String
        get() { rev.intValue; return prefs.getString("position", "TL") ?: "TL" }
        set(v) { prefs.edit().putString("position", v).apply(); touch() }

    var customX: Int
        get() { rev.intValue; return prefs.getInt("customX", 0) }
        set(v) { prefs.edit().putInt("customX", v).apply(); touch() }

    var customY: Int
        get() { rev.intValue; return prefs.getInt("customY", 0) }
        set(v) { prefs.edit().putInt("customY", v).apply(); touch() }

    /** Sürükleme sonrası konumu tek seferde kaydeder (aradaki yarım durumlar görünmesin). */
    fun setCustomPosition(x: Int, y: Int) {
        prefs.edit().putInt("customX", x).putInt("customY", y).putString("position", "custom").apply()
        touch()
    }

    var opacity: Float
        get() { rev.intValue; return prefs.getFloat("opacity", 1f) }
        set(v) { prefs.edit().putFloat("opacity", v).apply(); touch() }

    var scale: Float
        get() { rev.intValue; return prefs.getFloat("scale", 1f) }
        set(v) { prefs.edit().putFloat("scale", v).apply(); touch() }

    var clickThrough: Boolean
        get() { rev.intValue; return prefs.getBoolean("clickThrough", false) }
        set(v) { prefs.edit().putBoolean("clickThrough", v).apply(); touch() }

    /** FPS ölçümü ve kısıtlı sensörler için root (su) kullan. */
    var useRoot: Boolean
        get() { rev.intValue; return prefs.getBoolean("useRoot", false) }
        set(v) { prefs.edit().putBoolean("useRoot", v).apply(); touch() }
}
