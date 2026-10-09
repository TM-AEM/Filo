package com.filo.transfer.ui.model

import android.net.Uri
import java.text.DecimalFormat

/**
 * Lightweight, immutable UI presentation model for a user-selected file item.
 *
 * @param relativePath path relative to the picked folder root for folder transfers, e.g.
 * `"subdir/report.pdf"`; empty for individually picked files (legacy flat case).
 */
data class SelectedFileItem(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String,
    val formattedSize: String,
    val relativePath: String = ""
) {
    companion object {
        /**
         * Formats raw bytes into a human-readable string (e.g., "12.4 MB").
         */
        fun formatFileSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
            return DecimalFormat("#,##0.#").format(value) + " " + units[digitGroups]
        }
    }
}
