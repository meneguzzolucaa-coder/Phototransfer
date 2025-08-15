package com.example.lanfotoshare

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object ContentScanner {
    private val MEDIA_PREFIXES = listOf("image/", "video/")

    fun scanMedia(ctx: Context, tree: Uri, maxDepth: Int = 5): List<SelectedItem> {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return emptyList()
        val out = mutableListOf<SelectedItem>()

        fun walk(df: DocumentFile, depth: Int) {
            if (depth > maxDepth) return
            if (df.isDirectory) {
                df.listFiles().forEach { walk(it, depth + 1) }
            } else if (df.isFile) {
                val mime = df.type ?: ctx.contentResolver.getType(df.uri) ?: ""
                if (MEDIA_PREFIXES.any { mime.startsWith(it) }) {
                    out += SelectedItem(
                        uri = df.uri,
                        name = df.name ?: "media.bin",
                        mime = mime
                    )
                }
            }
        }

        walk(root, 0)
        return out
    }
}
