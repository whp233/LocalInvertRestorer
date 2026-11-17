package com.invrestore.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 图片读取：统一处理采样、EXIF 方向与内存上限。
 *
 * 长边默认压到 2600px —— 手机照片常见 4000×3000，全尺寸解码要 48MB，
 * 而检测在 640px 缩略图上做、还原只关心接缝附近的像素，
 * 2600px 足够保住文字细节，又把内存压到 27MB 以内。
 */
object ImageIo {

    const val MAX_EDGE = 2600
    const val THUMB_EDGE = 420

    fun decode(context: Context, uri: Uri, maxEdge: Int = MAX_EDGE): Bitmap {
        val bmp = if (Build.VERSION.SDK_INT >= 28) {
            decodeModern(context, uri, maxEdge)
        } else {
            decodeLegacy(context, uri, maxEdge)
        }
        if (bmp.width <= 0 || bmp.height <= 0) throw IOException("empty bitmap")
        return bmp
    }

    /** 只解码一张小图，用于列表缩略图，避免为每张图分配整幅位图。 */
    fun decodeThumb(context: Context, uri: Uri, maxEdge: Int = THUMB_EDGE): Bitmap {
        return if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longEdge = max(info.size.width, info.size.height)
                if (longEdge > maxEdge) {
                    val scale = maxEdge.toFloat() / longEdge
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt().coerceAtLeast(1),
                        (info.size.height * scale).roundToInt().coerceAtLeast(1)
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeLegacy(context, uri, maxEdge)
        }
    }

    fun thumbnail(src: Bitmap, maxEdge: Int): Bitmap {
        val longEdge = max(src.width, src.height)
        if (longEdge <= maxEdge) return src
        val scale = maxEdge.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * scale).roundToInt().coerceAtLeast(1),
            (src.height * scale).roundToInt().coerceAtLeast(1),
            true
        )
    }

    fun decodeFile(path: String): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts) ?: throw IOException("cannot decode $path")
    }

    fun displayName(context: Context, uri: Uri): String {
        val fallback = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) c.getString(idx) else null
                    } else null
                } ?: fallback
        } catch (t: Throwable) {
            fallback
        }
    }

    /** 去掉扩展名并清理文件名中的非法字符，用于生成保存名。 */
    fun baseName(displayName: String): String {
        val noExt = displayName.substringBeforeLast('.', displayName)
        val cleaned = noExt.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").trim('_', '.', ' ')
        return cleaned.take(40).ifBlank { "image" }
    }

    // ------------------------------------------------------------- internal

    private fun decodeModern(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        var bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longEdge = max(info.size.width, info.size.height)
            // 采样只取整数倍；先粗采样到 2 倍以内，再精确缩放到 maxEdge，
            // 这样 3000px 的图不会被直接砍到 1500px。
            if (longEdge > maxEdge * 2) {
                decoder.setTargetSampleSize(ceil(longEdge.toDouble() / maxEdge).toInt())
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        if (max(bmp.width, bmp.height) > maxEdge) {
            val scaled = thumbnail(bmp, maxEdge)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        return bmp
    }

    private fun decodeLegacy(context: Context, uri: Uri, maxEdge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: throw IOException("cannot open stream")
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("bad image")

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: throw IOException("cannot decode")

        if (max(bmp.width, bmp.height) > maxEdge) {
            val scaled = thumbnail(bmp, maxEdge)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }

        val angle = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                when (ExifInterface(ins).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        return if (angle == 0f) bmp else {
            val rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(angle) }, true
            )
            if (rotated !== bmp) bmp.recycle()
            rotated
        }
    }
}
