package com.bupt.schedule.data.local

import android.content.Context
import android.content.SharedPreferences

interface RefreshTimestampStore {
    fun getLastSuccessfulRefreshTime(): Long?
    fun saveLastSuccessfulRefreshTime(timeMs: Long)
    fun clear()
}

class RefreshMetadataStore(
    private val preferences: SharedPreferences,
) : RefreshTimestampStore {
    constructor(context: Context) : this(
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun getLastSuccessfulRefreshTime(): Long? {
        val value = preferences.getLong(KEY_LAST_SUCCESSFUL_REFRESH_AT, -1L)
        return if (value > 0L) value else null
    }

    override fun saveLastSuccessfulRefreshTime(timeMs: Long) {
        preferences.edit()
            .putLong(KEY_LAST_SUCCESSFUL_REFRESH_AT, timeMs)
            .apply()
    }

    override fun clear() {
        preferences.edit().remove(KEY_LAST_SUCCESSFUL_REFRESH_AT).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "schedule_refresh_meta_v1"
        const val KEY_LAST_SUCCESSFUL_REFRESH_AT = "last_successful_refresh_at"
    }
}
