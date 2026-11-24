package com.example.ridecalc

/**
 * Pure checks (no Android classes) deciding whether an image row from the media database is
 * a screenshot this app may read, and later delete. Every check must pass; any doubt = false.
 */
object ScreenshotGuard {
    const val READ_MAX_AGE_SECONDS = 30L
    const val DELETE_MAX_AGE_SECONDS = 120L
    const val MAX_SIZE_BYTES = 25L * 1024 * 1024
    private val okExtensions = setOf("jpg", "jpeg", "png", "webp")

    // Exact folders only (no "contains"), so e.g. Download/my-screenshots/ never qualifies.
    private val allowedDirs = setOf(
        "pictures/screenshots/",
        "dcim/screenshots/",
        "pictures/screenshot/",
        "dcim/screenshot/"
    )
    private const val STORAGE_ROOT = "/storage/emulated/0/"

    private fun normalizeDir(relativePath: String): String {
        var p = relativePath.lowercase().trim().trimStart('/')
        if (!p.endsWith("/")) p += "/"
        return p
    }

    fun isRecentScreenshot(
        displayName: String?,
        relativePath: String?,
        ageSeconds: Long,
        sizeBytes: Long,
        maxAgeSeconds: Long = READ_MAX_AGE_SECONDS
    ): Boolean {
        if (displayName.isNullOrBlank() || relativePath.isNullOrBlank()) return false
        val name = displayName.lowercase()
        if (name.contains('/') || name.contains('\\') || name.contains("..")) return false
        if (!name.startsWith("screenshot")) return false
        if (normalizeDir(relativePath) !in allowedDirs) return false
        val ext = name.substringAfterLast('.', "")
        if (ext !in okExtensions) return false
        if (ageSeconds < -5 || ageSeconds > maxAgeSeconds) return false
        if (sizeBytes <= 0 || sizeBytes > MAX_SIZE_BYTES) return false
        return true
    }

    /** Extra check used only for the plain-file fallback delete. */
    fun isSafeFilePath(absolutePath: String?, displayName: String?): Boolean {
        if (absolutePath.isNullOrBlank() || displayName.isNullOrBlank()) return false
        if (absolutePath.contains("..")) return false
        val lower = absolutePath.lowercase()
        if (!lower.startsWith(STORAGE_ROOT)) return false
        val rest = lower.removePrefix(STORAGE_ROOT)
        if (!rest.contains('/')) return false
        val dir = rest.substringBeforeLast('/') + "/"
        if (dir !in allowedDirs) return false
        return absolutePath.substringAfterLast('/') == displayName
    }
}
