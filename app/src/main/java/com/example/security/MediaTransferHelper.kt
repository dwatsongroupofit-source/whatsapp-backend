package com.example.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object MediaTransferHelper {
    private const val TAG = "MediaTransferHelper"
    private const val MAX_IMAGE_DIMENSION = 800

    /**
     * Copy any selected URI content to a local app-specific file in cache directory.
     * Returns the local File.
     */
    fun copyUriToLocalCache(context: Context, uri: Uri, fileName: String): File? {
        return try {
            val mediaDir = File(context.cacheDir, "WhatsChat_Media").apply {
                if (!exists()) mkdirs()
            }
            
            // Add UUID prefix to filename to prevent collisions
            val uniqueName = "${UUID.randomUUID()}_$fileName"
            val destFile = File(mediaDir, uniqueName)
            
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            Log.d(TAG, "Copied URI content to local cache: ${destFile.absolutePath}")
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying URI to local cache", e)
            null
        }
    }

    /**
     * Convert local cache file to Base64 payload string with metadata header.
     * Format: "base64:ext:fileName:base64Data"
     */
    fun fileToBase64Payload(file: File, isImage: Boolean): String? {
        return try {
            val bytes = if (isImage) {
                compressImage(file)
            } else {
                file.readBytes()
            }
            
            if (bytes == null || bytes.isEmpty()) return null
            
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            val ext = file.extension.lowercase()
            val name = file.name
            "base64:$ext:$name:$base64Data"
        } catch (e: Exception) {
            Log.e(TAG, "Error converting file to Base64 payload", e)
            null
        }
    }

    /**
     * Decode a Base64 payload string and save it to a local file in cache directory.
     * Returns the local File.
     */
    fun decodePayloadToLocalCache(context: Context, payload: String): File? {
        if (!payload.startsWith("base64:")) return null
        
        return try {
            val parts = payload.split(":", limit = 4)
            if (parts.size < 4) return null
            
            val ext = parts[1]
            val originalName = parts[2]
            val base64Data = parts[3]
            
            val bytes = Base64.decode(base64Data, Base64.NO_WRAP)
            
            val mediaDir = File(context.cacheDir, "WhatsChat_Media").apply {
                if (!exists()) mkdirs()
            }
            
            val localFile = File(mediaDir, originalName)
            FileOutputStream(localFile).use { outputStream ->
                outputStream.write(bytes)
            }
            Log.d(TAG, "Successfully decoded Base64 and saved to: ${localFile.absolutePath}")
            localFile
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding Base64 payload", e)
            null
        }
    }

    /**
     * Helper to compress selected image files to keep WebSocket payloads lightweight
     */
    private fun compressImage(file: File): ByteArray? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            
            var scale = 1
            while (options.outWidth / scale / 2 >= MAX_IMAGE_DIMENSION && 
                   options.outHeight / scale / 2 >= MAX_IMAGE_DIMENSION) {
                scale *= 2
            }
            
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = scale
            }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
            
            val out = ByteArrayOutputStream()
            // Compress as JPEG to keep size minimal
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error compressing image file", e)
            null
        }
    }
}
