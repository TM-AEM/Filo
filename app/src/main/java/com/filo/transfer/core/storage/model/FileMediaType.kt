package com.filo.transfer.core.storage.model

/**
 * Categorization of file types for Filo.
 * Determines the category using MIME type primarily, with fallback to file extensions.
 */
enum class FileMediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    APK,
    OTHER;

    companion object {
        /**
         * Resolves the media type from MIME type and optional file name.
         */
        fun fromMimeAndName(mimeType: String?, displayName: String?): FileMediaType {
            val normalizedMime = mimeType?.lowercase()?.trim() ?: ""
            val extension = displayName?.substringAfterLast('.', "")?.lowercase()?.trim() ?: ""

            return when {
                // Image
                normalizedMime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> IMAGE
                // Video
                normalizedMime.startsWith("video/") || extension in VIDEO_EXTENSIONS -> VIDEO
                // Audio
                normalizedMime.startsWith("audio/") || extension in AUDIO_EXTENSIONS -> AUDIO
                // APK
                normalizedMime == "application/vnd.android.package-archive" || extension == "apk" -> APK
                // Archive
                normalizedMime in ARCHIVE_MIMES || extension in ARCHIVE_EXTENSIONS -> ARCHIVE
                // Document
                normalizedMime in DOCUMENT_MIMES || normalizedMime.startsWith("text/") || extension in DOCUMENT_EXTENSIONS -> DOCUMENT
                else -> OTHER
            }
        }

        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic", "heif", "avif")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "flv", "wmv", "webm", "3gp", "m4v", "ts")
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "wma", "opus")
        private val ARCHIVE_EXTENSIONS = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz")
        private val DOCUMENT_EXTENSIONS = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "txt", "rtf", "csv", "json", "xml", "html", "md", "epub"
        )

        private val ARCHIVE_MIMES = setOf(
            "application/zip",
            "application/x-rar-compressed",
            "application/x-7z-compressed",
            "application/x-tar",
            "application/gzip",
            "application/x-bzip2"
        )

        private val DOCUMENT_MIMES = setOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/rtf",
            "application/epub+zip",
            "text/plain",
            "text/csv",
            "text/html"
        )
    }
}
