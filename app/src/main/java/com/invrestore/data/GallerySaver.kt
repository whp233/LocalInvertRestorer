package com.invrestore.data

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 保存与分享。
 *
 * Android 10+ 走 MediaStore，直接进系统相册的 Pictures/InvertRestore；
 * Android 9 及以下不申请存储权限，写到应用自己的图片目录，再通过
 * FileProvider 分享 —— 这样整个应用不需要任何运行时权限。
 */
object GallerySaver {

    const val ALBUM = "InvertRestore"

    data class Item(val uri: Uri, val album: String)

    data class Batch(val count: Int, val album: String, val uris: List<Uri>)

    fun saveBitmap(context: Context, bmp: Bitmap, baseName: String, suffix: String): Item {
        val name = fileName(baseName, suffix, 0)
        if (Build.VERSION.SDK_INT >= 29) {
            val uri = insertMediaStore(context, bmp, name)
            return Item(uri, "Pictures/$ALBUM")
        }
        val file = writeAppDir(context, bmp, name)
        return Item(shareUri(context, file), "应用目录/Pictures/$ALBUM")
    }

    fun saveFiles(
        context: Context,
        files: List<Pair<File, String>>,
        suffix: String
    ): Batch {
        var album = "Pictures/$ALBUM"
        val uris = ArrayList<Uri>(files.size)
        files.forEachIndexed { index, (file, baseName) ->
            val bmp = try {
                ImageIo.decodeFile(file.absolutePath)
            } catch (t: Throwable) {
                null
            } ?: return@forEachIndexed
            try {
                val name = fileName(baseName, suffix, index)
                if (Build.VERSION.SDK_INT >= 29) {
                    uris += insertMediaStore(context, bmp, name)
                } else {
                    val out = writeAppDir(context, bmp, name)
                    album = "应用目录/Pictures/$ALBUM"
                    uris += shareUri(context, out)
                }
            } finally {
                bmp.recycle()
            }
        }
        return Batch(uris.size, album, uris)
    }

    /** 为“直接分享”准备一个缓存文件，返回可被其他应用读取的 content:// Uri。 */
    fun prepareShare(
        context: Context,
        bmp: Bitmap,
        baseName: String,
        suffix: String
    ): Uri {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, fileName(baseName, suffix, 0))
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return shareUri(context, file)
    }

    fun launchShare(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uris[0])
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "image/png"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, context.getString(com.invrestore.R.string.action_share))
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }
    }

    fun deleteQuietly(file: File?) {
        runCatching { file?.delete() }
    }

    // ------------------------------------------------------------- internal

    private fun fileName(baseName: String, suffix: String, index: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val tail = if (index > 0) "_$index" else ""
        return "${ImageIo.baseName(baseName)}_${suffix}_${stamp}$tail.png"
    }

    private fun insertMediaStore(context: Context, bmp: Bitmap, name: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("insert failed")
        try {
            resolver.openOutputStream(uri)?.use { os ->
                if (!bmp.compress(Bitmap.CompressFormat.PNG, 100, os)) throw IOException("compress failed")
            } ?: throw IOException("no output stream")
            val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            return uri
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
    }

    private fun writeAppDir(context: Context, bmp: Bitmap, name: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: context.filesDir
        val dir = File(root, ALBUM).apply { mkdirs() }
        val file = File(dir, name)
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file
    }

    private fun shareUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
