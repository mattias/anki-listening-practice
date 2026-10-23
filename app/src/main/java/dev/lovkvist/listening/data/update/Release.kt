package dev.lovkvist.listening.data.update

import org.json.JSONObject

/** A published GitHub release with an installable APK. */
data class Release(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    /** Hex SHA-256 of the APK from GitHub's asset digest, if it has one. */
    val sha256: String?,
    val pageUrl: String,
)

/** Parses GitHub's "latest release" JSON; null if it has no .apk asset. */
internal fun parseRelease(json: String): Release? {
    val j = JSONObject(json)
    if (j.optBoolean("draft") || j.optBoolean("prerelease")) return null
    val assets = j.optJSONArray("assets") ?: return null
    val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) } ?: return null
    return Release(
        version = j.getString("tag_name").removePrefix("v"),
        notes = j.optString("body"),
        apkUrl = apk.getString("browser_download_url"),
        apkSize = apk.optLong("size"),
        sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
        pageUrl = j.optString("html_url"),
    )
}

/** True if version [latest] ("0.3.0") is newer than [current] ("0.2.0"); suffixes like "-debug" are ignored. */
internal fun isNewer(latest: String, current: String): Boolean {
    fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}
