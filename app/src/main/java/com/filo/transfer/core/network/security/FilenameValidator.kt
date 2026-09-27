package com.filo.transfer.core.network.security

/**
 * Validates and sanitizes incoming filenames from the network to protect against
 * path traversal attacks (../), absolute path overrides, and reserved/illegal filesystem names.
 */
object FilenameValidator {

    private const val MAX_FILENAME_LENGTH = 255

    private val RESERVED_NAMES = setOf(
        "con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    )

    private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\x00-\\x1F]")

    /**
     * Checks if the given filename is strictly safe without requiring modifications.
     */
    fun isSafe(filename: String?): Boolean {
        if (filename.isNullOrBlank()) return false
        if (filename.length > MAX_FILENAME_LENGTH) return false

        // Check for directory traversal
        if (filename.contains("..") || filename.contains('/') || filename.contains('\\')) {
            return false
        }

        // Check for null bytes or illegal control characters
        if (ILLEGAL_CHARS.containsMatchIn(filename)) {
            return false
        }

        // Check for reserved filenames (case-insensitive)
        val nameWithoutExt = filename.substringBeforeLast('.').lowercase().trim()
        if (nameWithoutExt in RESERVED_NAMES) {
            return false
        }

        return true
    }

    /**
     * Sanitizes an incoming filename, stripping directory traversal components and illegal characters,
     * guaranteeing a safe basename.
     */
    fun sanitize(rawName: String?, fallbackPrefix: String = "file"): String {
        if (rawName.isNullOrBlank()) {
            return "${fallbackPrefix}_${System.currentTimeMillis()}"
        }

        // 1. Extract only the base name (remove any directory path components)
        var name = rawName.substringAfterLast('/').substringAfterLast('\\')

        // 2. Remove directory traversal sequences
        name = name.replace("..", "_")

        // 3. Remove illegal control characters and path symbols
        name = ILLEGAL_CHARS.replace(name, "_")

        // 4. Trim spaces and dots from start and end
        name = name.trim().trimStart('.').trimEnd('.')

        // 5. Check if empty after cleaning
        if (name.isBlank()) {
            name = "${fallbackPrefix}_${System.currentTimeMillis()}"
        }

        // 6. Check reserved Windows/FAT32 names
        val basePart = name.substringBeforeLast('.').lowercase()
        if (basePart in RESERVED_NAMES) {
            name = "safe_$name"
        }

        // 7. Enforce maximum length constraint
        if (name.length > MAX_FILENAME_LENGTH) {
            val ext = name.substringAfterLast('.', "")
            val maxBaseLen = if (ext.isNotEmpty()) MAX_FILENAME_LENGTH - ext.length - 1 else MAX_FILENAME_LENGTH
            val truncatedBase = name.substringBeforeLast('.').take(maxBaseLen)
            name = if (ext.isNotEmpty()) "$truncatedBase.$ext" else truncatedBase
        }

        return name
    }
}
