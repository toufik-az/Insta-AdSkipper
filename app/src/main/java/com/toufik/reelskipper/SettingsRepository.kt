package com.toufik.reelskipper

import android.content.Context
import android.content.SharedPreferences

/** Simple settings: only ad-skip enable + keyword list. */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var adSkipEnabled: Boolean
        get() = prefs.getBoolean(KEY_AD_SKIP, true)
        set(value) = prefs.edit().putBoolean(KEY_AD_SKIP, value).apply()

    var adKeywordsRaw: String
        get() = prefs.getString(KEY_KEYWORDS, DEFAULT_KEYWORDS) ?: DEFAULT_KEYWORDS
        set(value) = prefs.edit().putString(KEY_KEYWORDS, value).apply()

    val adKeywords: List<String>
        get() = adKeywordsRaw.split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    fun registerListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unregisterListener(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    companion object {
        const val PREFS_NAME = "reel_skipper_prefs"
        const val KEY_AD_SKIP = "ad_skip_enabled"
        const val KEY_KEYWORDS = "ad_keywords"
        const val DEFAULT_KEYWORDS = "Sponsored, Paid partnership, Ad"
    }
}
