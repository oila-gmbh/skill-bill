package dev.skillbill.intellij.application

import dev.skillbill.intellij.domain.DEFAULT_REFRESH_INTERVAL_SECONDS
import dev.skillbill.intellij.domain.LastKnownDisplayCache

/**
 * Lightweight preference and optional last-known display cache port.
 * Persists only settings (CLI executable, refresh interval) and an
 * observation-time-marked display cache. Never writes Skill Bill runtime DBs.
 */
interface PreferenceCachePort {
    fun getCliExecutableOverride(): String?

    fun setCliExecutableOverride(path: String?)

    fun getRefreshIntervalSeconds(): Long

    fun setRefreshIntervalSeconds(seconds: Long)

    fun getLastKnownDisplayCache(): LastKnownDisplayCache?

    fun setLastKnownDisplayCache(cache: LastKnownDisplayCache?)

    companion object {
        fun defaultRefreshIntervalSeconds(): Long = DEFAULT_REFRESH_INTERVAL_SECONDS
    }
}
