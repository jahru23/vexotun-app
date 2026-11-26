package com.vexotun.app.data

import android.content.Context
import com.vexotun.app.model.VexotunConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Multi-process-safe JSON file storage for Vexotun configuration.
 * SharedPreferences(MODE_PRIVATE) caches per-process and goes stale across MainActivity and :vpn.
 */
class ConfigStore(context: Context) {
    private val appContext = context.applicationContext
    private val configFile = File(appContext.filesDir, CONFIG_FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun load(): VexotunConfig {
        if (!configFile.exists()) {
            val legacy = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CONFIG, null)
            if (!legacy.isNullOrBlank()) {
                val parsed = runCatching { json.decodeFromString<VexotunConfig>(legacy) }.getOrNull()
                if (parsed != null) {
                    writeAtomic(legacy)
                    return parsed
                }
            }
            return VexotunConfig()
        }
        val raw = runCatching { configFile.readText(Charsets.UTF_8) }.getOrNull()
            ?: return VexotunConfig()
        return runCatching { json.decodeFromString<VexotunConfig>(raw) }
            .getOrElse { VexotunConfig() }
    }

    @Synchronized
    fun save(config: VexotunConfig) {
        val raw = json.encodeToString(config)
        writeAtomic(raw)
    }

    private fun writeAtomic(raw: String) {
        runCatching {
            val tmp = File(appContext.filesDir, "$CONFIG_FILE_NAME.tmp")
            tmp.writeText(raw, Charsets.UTF_8)
            if (!tmp.renameTo(configFile)) {
                configFile.writeText(raw, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    companion object {
        private const val CONFIG_FILE_NAME = "vexotun_config.json"
        private const val LEGACY_PREFS = "vexotun_prefs"
        private const val KEY_CONFIG = "active_config_v2"
    }
}
