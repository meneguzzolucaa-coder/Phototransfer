package com.example.lanfotoshare

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object SaveMedia {

    fun saveToCamera(cr: ContentResolver, displayName: String, mime: String, input: InputStream): Boolean {
        return if (mime.startsWith("video/")) {
            saveVideo(cr, displayName, mime, input)
        } else {
            saveImage(cr, displayName, mime, input)
        }
    }

    private fun uniqueLegacyFile(dir: File, name: String): File {
        var base = name
        var ext = ""
        val dot = name.lastIndexOf('.')
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot) }
        var f = File(dir, name)
        var i = 2
        while (f.exists()) {
            f = File(dir, base + " (" + i + ")" + ext)
            i++
        }
        return f
    }

    private fun saveImage(cr: ContentResolver, displayName: String, mime: String, input: InputStream): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            cr.openOutputStream(uri)?.use { out -> input.copyTo(out) } ?: return false
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            true
        } else {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val cameraDir = File(dcim, "Camera").apply { if (!exists()) mkdirs() }
            val file = uniqueLegacyFile(cameraDir, displayName)
            FileOutputStream(file).use { out -> input.copyTo(out) }
            true
        }
    }

    private fun saveVideo(cr: ContentResolver, displayName: String, mime: String, input: InputStream): Boolean {
        return if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
            cr.openOutputStream(uri)?.use { out -> input.copyTo(out) } ?: return false
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            cr.update(uri, values, null, null)
            true
        } else {
            val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
            val cameraDir = File(dcim, "Camera").apply { if (!exists()) mkdirs() }
            val file = uniqueLegacyFile(cameraDir, displayName)
            FileOutputStream(file).use { out -> input.copyTo(out) }
            true
        }
    }
}