package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.shilapi.xcertplay.hud.Kx11DimSettings
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/** Separate provider identity for exposing cached album covers to KX11 DIMMediaInteractioncService. */
class Kx11ArtworkProvider : FileProvider()

/**
 * Writes resized JPEG album covers into cacheDir/kx11-dim-artwork/ with monotonically increasing
 * filenames so `DIMMediaInteractioncService` detects every cover change via its `mLast_img_uri`
 * check, and grants temporary read URI permission to `com.autolink.diminteraction`.
 */
internal object Kx11ArtworkShare {
    private const val TAG = "DiPlay-KX11Artwork"
    private const val DIR_NAME = "kx11-dim-artwork"
    private const val MAX_COVER_EDGE = 256
    private const val KEEP_FILES = 6

    private val revision = AtomicInteger(0)
    private var lastBitmapRef: Bitmap? = null
    private var lastUri: Uri? = null

    @Synchronized
    fun publish(context: Context, bitmap: Bitmap?): Uri? {
        if (bitmap == null || bitmap.isRecycled) {
            lastBitmapRef = null
            lastUri = null
            return null
        }
        if (bitmap === lastBitmapRef && lastUri != null) {
            return lastUri
        }
        val app = context.applicationContext
        val dir = File(app.cacheDir, DIR_NAME).apply { mkdirs() }
        val rev = revision.incrementAndGet()
        val outFile = File(dir, "cover_$rev.jpg")
        val saved = runCatching {
            val scaled = scaleIfNeeded(bitmap)
            try {
                FileOutputStream(outFile).use { stream ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
            val authority = "${app.packageName}.kx11-artwork"
            val uri = FileProvider.getUriForFile(app, authority, outFile)
            app.grantUriPermission(
                Kx11DimSettings.DIM_PACKAGE_NAME,
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            cleanupOldFiles(dir, keepName = outFile.name)
            uri
        }.onFailure {
            Log.w(TAG, "Failed to share album cover: ${it.javaClass.simpleName}: ${it.message}")
            outFile.delete()
        }.getOrNull()
        lastBitmapRef = if (saved != null) bitmap else null
        lastUri = saved
        return saved
    }

    @Synchronized
    fun clear(context: Context) {
        lastBitmapRef = null
        lastUri = null
        runCatching {
            File(context.applicationContext.cacheDir, DIR_NAME).listFiles()?.forEach { it.delete() }
        }
    }

    private fun scaleIfNeeded(source: Bitmap): Bitmap {
        val maxSide = maxOf(source.width, source.height)
        if (maxSide <= MAX_COVER_EDGE || maxSide <= 0) return source
        val ratio = MAX_COVER_EDGE.toFloat() / maxSide.toFloat()
        val w = (source.width * ratio).toInt().coerceAtLeast(1)
        val h = (source.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, w, h, true)
    }

    private fun cleanupOldFiles(dir: File, keepName: String) {
        val files = dir.listFiles()?.sortedByDescending { parseRevision(it.name) } ?: return
        var kept = 0
        for (file in files) {
            if (file.name == keepName || kept < KEEP_FILES) {
                kept++
            } else {
                file.delete()
            }
        }
    }

    private fun parseRevision(name: String): Long =
        name.removePrefix("cover_").removeSuffix(".jpg").toLongOrNull() ?: -1L
}
