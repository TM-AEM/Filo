package com.filo.transfer.core.network.security

import java.io.File

/**
 * Validates and normalizes a sender-supplied relative file path used for folder transfers.
 *
 * A [relativePath] describes where a file should land *inside* the receiver's trusted
 * destination root, e.g. `"docs/work/report.pdf"`. Because it is attacker-controlled
 * input, every value must be validated before it is ever used to build a filesystem path.
 *
 * An empty [relativePath] is the legacy "flat" case: the file lands directly in the
 * destination root, exactly as it did before folder transfer existed.
 *
 * Rules enforced here:
 * - Empty / blank means "no relative path" (legacy, always allowed).
 * - Absolute paths are rejected (`/`, `\`, Windows drive letters).
 * - Backslashes are rejected; only `/` is accepted as a separator.
 * - `.` and `..` components are rejected, so the path can never escape.
 * - Empty components are rejected (`a//b`, trailing `/`).
 * - Each component must independently satisfy [FilenameValidator.isSafe].
 * - Depth and total length are bounded to keep manifest and UI sane.
 *
 * The class also offers [resolveWithinRoot] which turns a validated relative path into a
 * concrete [File] guaranteed to stay inside the destination root.
 */
object RelativePathValidator {

    /** Maximum number of path components (directory nesting depth + 1). */
    const val MAX_DEPTH = 16

    /** Maximum total length in characters of a validated relative path. */
    const val MAX_PATH_LENGTH = 1024

    private const val SEPARATOR = '/'

    private val WINDOWS_DRIVE_PREFIX = Regex("^[A-Za-z]:.*")

    /**
     * Validates [raw] and returns the normalized relative path (using `/` as separator),
     * or `""` when [raw] is empty/blank, or `null` when it violates any rule above.
     *
     * Callers MUST treat a `null` result as untrusted input and refuse the transfer.
     */
    fun validate(raw: String?): String? {
        if (raw.isNullOrBlank()) return ""

        val trimmed = raw.trim()

        if (trimmed.length > MAX_PATH_LENGTH) return null
        if (trimmed.startsWith(SEPARATOR)) return null
        if (trimmed.startsWith('\\')) return null
        if (WINDOWS_DRIVE_PREFIX.matches(trimmed)) return null

        val components = trimmed.split(SEPARATOR)
        if (components.size > MAX_DEPTH) return null

        val normalized = StringBuilder()
        for ((index, component) in components.withIndex()) {
            when {
                component.isEmpty() -> return null
                component == "." || component == ".." -> return null
                !FilenameValidator.isSafe(component) -> return null
                else -> {
                    if (index > 0) normalized.append(SEPARATOR)
                    normalized.append(component)
                }
            }
        }

        return normalized.toString()
    }

    /** `true` when [raw] passes [validate] (including the legacy empty case). */
    fun isSafe(raw: String?): Boolean = validate(raw) != null

    /**
     * Resolves [relativePath] (which MUST have been produced by [validate]) against
     * [destinationRoot], creating any missing parent directories.
     *
     * @return the resolved [File], guaranteed to be inside [destinationRoot]
     * @throws SecurityException if the resolved path escapes the destination root
     */
    fun resolveWithinRoot(destinationRoot: File, relativePath: String): File {
        if (relativePath.isEmpty()) {
            return destinationRoot
        }
        if (FilenameValidator.isSafe(relativePath)) {
            return File(destinationRoot, relativePath).apply { parentFile?.mkdirs() }
        }

        val resolved = File(destinationRoot, relativePath)
        val rootCanonical = destinationRoot.canonicalPath
        val resolvedCanonical = resolved.canonicalPath
        if (!resolvedCanonical.startsWith(rootCanonical + File.separator) &&
            resolvedCanonical != rootCanonical
        ) {
            throw SecurityException(
                "Resolved path escapes destination root: $resolvedCanonical"
            )
        }
        resolved.parentFile?.mkdirs()
        return resolved
    }
}
