package com.ams.wallverse

import android.content.Context
import org.json.JSONArray

object GenerationLimiter {
    private const val KEY_TIMESTAMPS_JSON = "generation_timestamps_json"
    private const val LEGACY_KEY_TIMESTAMPS_SET = "generation_timestamps"
    private const val HOURS_24_MS = 24L * 60 * 60 * 1000

    private fun prefs(context: Context, scopeKey: String): android.content.SharedPreferences {
        val safe = scopeKey.ifBlank { "guest" }
        return context.applicationContext.getSharedPreferences("generation_prefs_$safe", Context.MODE_PRIVATE)
    }

    @Synchronized
    fun canGenerate(context: Context, limit: Int, scopeKey: String = "guest"): Boolean {
        val valid = getValidTimestamps(context, scopeKey)
        return valid.size < limit
    }

    @Synchronized
    fun recordGeneration(context: Context, scopeKey: String = "guest") {
        val p = prefs(context, scopeKey)
        val list = getValidTimestamps(context, scopeKey).toMutableList()
        list.add(System.currentTimeMillis())
        p.edit().putString(KEY_TIMESTAMPS_JSON, list.toJson()).apply()
    }

    @Synchronized
    fun getRemainingCount(context: Context, limit: Int, scopeKey: String = "guest"): Int {
        val valid = getValidTimestamps(context, scopeKey)
        return (limit - valid.size).coerceAtLeast(0)
    }

    private fun List<Long>.toJson(): String {
        val arr = JSONArray()
        forEach { arr.put(it) }
        return arr.toString()
    }

    private fun String?.toLongListOrEmpty(): MutableList<Long> {
        if (this.isNullOrBlank()) return mutableListOf()
        return try {
            val arr = JSONArray(this)
            MutableList(arr.length()) { i -> arr.getLong(i) }
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    @Synchronized
    private fun getValidTimestamps(context: Context, scopeKey: String): List<Long> {
        val p = prefs(context, scopeKey)
        migrateLegacyIfNeeded(p)

        val now = System.currentTimeMillis()
        val raw = p.getString(KEY_TIMESTAMPS_JSON, null).toLongListOrEmpty()
        val pruned = raw.filter { (now - it) < HOURS_24_MS }

        if (pruned.size != raw.size) {
            p.edit().putString(KEY_TIMESTAMPS_JSON, pruned.toJson()).apply()
        }
        return pruned
    }

    private fun migrateLegacyIfNeeded(prefs: android.content.SharedPreferences) {
        if (!prefs.contains(LEGACY_KEY_TIMESTAMPS_SET)) return
        val legacy = prefs.getStringSet(LEGACY_KEY_TIMESTAMPS_SET, emptySet()) ?: emptySet()
        val migrated = legacy.mapNotNull { it.toLongOrNull() }
        prefs.edit()
            .remove(LEGACY_KEY_TIMESTAMPS_SET)
            .putString(KEY_TIMESTAMPS_JSON, migrated.toJson())
            .apply()
    }
}

