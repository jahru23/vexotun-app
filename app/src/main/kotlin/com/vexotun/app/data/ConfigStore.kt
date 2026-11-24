package com.vexotun.app.data

import android.content.Context
import com.vexotun.app.model.VexotunConfig
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConfigStore(context: Context) {
    private val prefs = context.getSharedPreferences("vexotun_prefs", Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun load(): VexotunConfig {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return VexotunConfig()
        val loaded = runCatching { json.decodeFromString<VexotunConfig>(raw) }
            .getOrElse { VexotunConfig() }
        return if (loaded.profiles.isEmpty()) VexotunConfig() else loaded
    }

    fun save(config: VexotunConfig) {
        val raw = json.encodeToString(config)
        prefs.edit().putString(KEY_CONFIG, raw).apply()
    }

    companion object {
        private const val KEY_CONFIG = "active_config_v2"
    }
}
