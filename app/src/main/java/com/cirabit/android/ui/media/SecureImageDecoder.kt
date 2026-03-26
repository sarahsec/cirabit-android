package com.cirabit.android.ui.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.cirabit.android.util.AppConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object SecureImageDecoder {

    private const val TAG = "SecureImageDecoder"

    suspend fun decodeForDisplay(
        path: String,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap? = withContext(Dispatchers.IO) {
        if (path.isBlank()) return@withContext null

        val safeTargetWidth = targetWidthPx.coerceAtLeast(1)
        val safeTargetHeight = targetHeightPx.coerceAtLeast(1)

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(path, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Invalid image bounds for $path")
            return@withContext null
        }

        val totalPixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
        if (totalPixels > AppConstants.Media.MAX_IMAGE_PIXELS) {
            Log.w(
                TAG,
                "Rejecting image decode for $path due to excessive pixels: ${bounds.outWidth}x${bounds.outHeight} ($totalPixels)"
            )
            return@withContext null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, safeTargetWidth, safeTargetHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        try {
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError decoding image: $path", oom)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode image: $path (${e.message})")
            null
        }
    }

    private fun calculateInSampleSize(
        srcWidth: Int,
        srcHeight: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
                if (inSampleSize >= Int.MAX_VALUE / 2) {
                    break
                }
            }
        }
        return inSampleSize.coerceAtLeast(1)
    }
}
