package com.example.lanfotoshare

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

object ContentScanner {
    private val PREFIXES = listOf("image/","video/")

    fun scanMedia(cr: ContentResolver, tree: Uri, maxDepth: Int = 3): List<SelectedItem> {
        val root = DocumentFile.fromTreeUri(cr, tree) ?: return emptyList()
        val out = mutableListOf<SelectedItem>()
        fun walk(dir: DocumentFile, depth: Int) {
            if (depth > maxDepth) return
            dir.listFiles().forEach { f ->
                if (f.isDirectory) {
                    walk(f, depth + 1)
                } else if ((f.type ?: "").let { t -> PREFIXES.any { t.startsWith(it) } }) {
                    out.add(SelectedItem(f.uri, f.name ?: "media.bin", f.type ?: "application/octet-stream"))
                }
            }
        }
        if (root.isDirectory) walk(root, 0)
        return out
    }
}