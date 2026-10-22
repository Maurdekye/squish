package io.github.maurdekye.squish

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var limitMb: Int
        get() = prefs.getInt(KEY_LIMIT, DEFAULT_LIMIT_MB)
        set(value) = prefs.edit().putInt(KEY_LIMIT, value.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)).apply()

    /** Decimal megabytes: a little under Discord's limit whether it counts MB or MiB. */
    val limitBytes: Long get() = limitMb * 1_000_000L

    companion object {
        private const val KEY_LIMIT = "limit_mb"

        /** Discord's free upload limit, checked 2026-10-22 (raised from 10 MB to 20 MB on 2026-08-13). */
        const val DEFAULT_LIMIT_MB = 20
        const val NITRO_BASIC_MB = 50
        const val NITRO_MB = 500
        const val MIN_LIMIT_MB = 1
        const val MAX_LIMIT_MB = 10_000
    }
}
