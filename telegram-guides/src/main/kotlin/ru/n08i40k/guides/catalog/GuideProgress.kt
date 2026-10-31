package ru.n08i40k.guides.catalog

import android.content.SharedPreferences

class GuideProgress(private val preferences: SharedPreferences, private val key: String) {
    fun completed(): Set<String> =
        preferences.getStringSet(key, null).orEmpty().toSet()

    fun complete(id: String) =
        preferences.edit().putStringSet(key, completed() + id).apply()

    companion object {
        fun available(ids: List<String>, completed: Set<String>, index: Int, debug: Boolean): Boolean =
            index in ids.indices && (debug || ids[index] in completed || ids.take(index).all { it in completed })
    }
}
