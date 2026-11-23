package com.liu.gallery.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 剪辑导出：视频时间范围裁剪 + 图片滤镜/旋转，结果一律**另存为新文件**（MediaStore 新条目），原文件不动。
 * - 视频：MediaExtractor + MediaMuxer 无损搬运（不重编码），起点对齐前一个关键帧、时间戳归零，
 *   视频轨与音频轨一并复制；
 * - 图片：先按 EXIF 摆正再落盘 JPEG，滤镜参数与预览共用同一份颜色矩阵（所见即所得）。
 */

/** 图片滤镜（剪辑界面 chips；“原图” = null，不进枚举） */
enum class ImageFilter(val label: String) {
    BLACK_WHITE("黑白"),
    SATURATE_HIGH("鲜艳"),
    SATURATE_LOW("柔和"),
    BRIGHT("明亮"),
    DARK("暗调"),
}

/** 滤镜 → 颜色矩阵；预览与落盘共用同一套参数 */
fun ImageFilter.toColorMatrix(): ColorMatrix = ColorMatrix().also { cm ->
    when (this) {
        ImageFilter.BLACK_WHITE -> cm.setSaturation(0f)
        ImageFilter.SATURATE_HIGH -> cm.setSaturation(2f)
        ImageFilter.SATURATE_LOW -> cm.setSaturation(0.4f)
        ImageFilter.BRIGHT -> cm.setScale(1.2f, 1.2f, 1.2f, 1f)
        ImageFilter.DARK -> cm.setScale(0.7f, 0.7f, 0.7f, 1f)
    }
}

/**
 * 解码图片供剪辑使用：按 EXIF 方向摆正；maxDim > 0 时按最长边降采样（预览用），0 = 原始分辨率。
 * 返回 null 表示解码失败（格式不支持 / 内存不足等）。
 */
fun decodeEditableImage(context: Context, uri: Uri, maxDim: Int): Bitmap? {
    try {
        // 先只读文件头拿原始尺寸，用于计算采样率
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // inSampleSize 只支持 2 的幂：取“结果仍不小于 maxDim”的最大档位
        var sample = 1
        if (maxDim > 0) {
            while (bounds.outWidth / (sample * 2) >= maxDim ||
                bounds.outHeight / (sample * 2) >= maxDim
            ) {
                sample *= 2
            }
        }

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        // EXIF 方向（BitmapFactory 不会自动应用，这里显式摆正）
        val orientation = context.contentResolver.openInputStream(uri)?.use { stream ->
            runCatching {
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                m.postRotate(90f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                m.postRotate(270f)
                m.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
        }
        return if (m.isIdentity) raw
        else Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
    } catch (e: Throwable) {
        return null
    }
}

/** 应用滤镜 + 90° 旋转，返回结果位图；无变换时直接返回原图（不额外分配） */
fun applyImageEdit(src: Bitmap, filter: ImageFilter?, rotationDeg: Int): Bitmap {
    val normalized = ((rotationDeg % 360) + 360) % 360
    val rotated = if (normalized == 0) src else {
        val m = Matrix().apply { postRotate(normalized.toFloat()) }
        Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }
    if (filter == null) return rotated
    val out = Bitmap.createBitmap(rotated.width, rotated.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    paint.colorFilter = ColorMatrixColorFilter(filter.toColorMatrix())
    // 源图透明区（PNG 等）先铺白底，避免 JPEG 落盘时出现黑块
    canvas.drawColor(Color.WHITE)
    canvas.drawBitmap(rotated, 0f, 0f, paint)
    return out
}

/**
 * 另存新文件的剪辑导出器：全部写入 MediaStore 新条目，原文件保持不动。
 * 新文件名 = 原名_剪辑_yyMMdd_HHmmss.ext，沿用原文件所在目录。
 */
object TrimExporter {

    private val nameStamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyMMdd_HHmmss")

    /** 另存目标：名字与位置（预 Q 直写目录 / Q+ 走 RELATIVE_PATH） */
    private data class ExportTarget(
        val displayName: String,
        val mimeType: String,
        val relativePath: String,
        val legacyDir: String?,
    )

    /** 视频裁剪另存：返回新条目 Uri，失败返回 null */
    fun exportVideoClip(context: Context, item: MediaItem, startMs: Long, endMs: Long): Uri? {
        if (!item.isVideo || endMs <= startMs) return null
        val tmp = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        return try {
            if (!remuxClip(context, item.uri, startMs, endMs, tmp)) {
                null
            } else {
                writeNewEntry(context, targetFor(item, "mp4", "video/mp4"), isVideo = true) { out ->
                    tmp.inputStream().use { it.copyTo(out) }
                }
            }
        } catch (e: Throwable) {
            null
        } finally {
            tmp.delete()
        }
    }

    /** 图片滤镜/旋转另存（JPEG）：返回新条目 Uri，失败返回 null */
    fun exportImageEdit(
        context: Context,
        item: MediaItem,
        filter: ImageFilter?,
        rotationDeg: Int,
    ): Uri? {
        if (item.isVideo) return null
        return try {
            val src = decodeEditableImage(context, item.uri, 0) ?: return null
            val out = applyImageEdit(src, filter, rotationDeg)
            writeNewEntry(context, targetFor(item, "jpg", "image/jpeg"), isVideo = false) { stream ->
                out.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            }
        } catch (e: Throwable) {
            null
        }
    }

    /** 生成新文件名与所在目录（沿用原目录，追加“剪辑 + 时间戳”避免重名） */
    private fun targetFor(item: MediaItem, ext: String, mime: String): ExportTarget {
        val base = item.name.substringBeforeLast('.').ifBlank { "media" }
        val stamp = LocalDateTime.now().format(nameStamp)
        return ExportTarget(
            displayName = "${base}_剪辑_$stamp.$ext",
            mimeType = mime,
            relativePath = item.relativePath.ifBlank { Environment.DIRECTORY_DCIM + "/Camera" },
            legacyDir = item.filePath?.substringBeforeLast('/'),
        )
    }

    /**
     * 无损裁剪：只搬数据不重编码。
     * 起点对齐到 startMs 之前的最近关键帧（originUs），音频轨一并复制，
     * 所有样本时间戳减去 originUs 归零，避免裁剪后开头停顿/黑屏。
     */
    private fun remuxClip(
        context: Context,
        source: Uri,
        startMs: Long,
        endMs: Long,
        out: File,
    ): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = context.contentResolver.openFileDescriptor(source, "r") ?: return false
            extractor.setDataSource(pfd.fileDescriptor)

            // 找视频轨与音频轨（track 0 不一定是视频）
            var videoIndex = -1
            var audioIndex = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                when {
                    videoIndex < 0 && mime.startsWith("video/") -> videoIndex = i
                    audioIndex < 0 && mime.startsWith("audio/") -> audioIndex = i
                }
            }
            if (videoIndex < 0) return false

            // 先只选视频轨，向起点前对齐到最近关键帧，它就是输出的真实起点
            extractor.selectTrack(videoIndex)
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val originUs = extractor.sampleTime
            if (originUs < 0) return false

            // 再加入音频轨并重定位：两轨都从 originUs 附近开始
            if (audioIndex >= 0) extractor.selectTrack(audioIndex)
            extractor.seekTo(originUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val videoFormat = extractor.getTrackFormat(videoIndex)
            val rotation = if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                videoFormat.getInteger(MediaFormat.KEY_ROTATION)
            } else 0
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (rotation % 360 != 0) muxer.setOrientationHint(rotation)

            val trackMap = HashMap<Int, Int>(2)
            trackMap[videoIndex] = muxer.addTrack(videoFormat)
            if (audioIndex >= 0) {
                trackMap[audioIndex] = muxer.addTrack(extractor.getTrackFormat(audioIndex))
            }
            muxer.start()

            val buffer = ByteBuffer.allocate(1 shl 23) // 8 MB，覆盖超大关键帧
            val info = MediaCodec.BufferInfo()
            val endUs = endMs * 1000
            var wroteVideo = false
            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) break
                val sampleUs = extractor.sampleTime
                if (sampleUs < 0 || sampleUs > endUs) break
                // 起点前残留的样本（多轨定位误差）直接跳过
                if (sampleUs < originUs) {
                    extractor.advance()
                    continue
                }
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val dstTrack = trackMap[trackIndex]
                if (dstTrack == null) {
                    extractor.advance()
                    continue
                }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = sampleUs - originUs // 归零：首帧从 0 开始
                info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                    MediaCodec.BUFFER_FLAG_KEY_FRAME
                } else {
                    0
                }
                muxer.writeSampleData(dstTrack, buffer, info)
                if (trackIndex == videoIndex) wroteVideo = true
                extractor.advance()
            }
            if (wroteVideo) runCatching { muxer.stop() }
            return wroteVideo
        } catch (e: Throwable) {
            return false
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
            runCatching { pfd?.close() }
        }
    }

    /**
     * 写入 MediaStore 新条目：
     * - Android 10+：RELATIVE_PATH 插入 + IS_PENDING 期间写入，完成后再发布；
     * - Android 9 及以下：直写原目录，随后媒体扫描（等待回调拿到条目 Uri）。
     */
    private fun writeNewEntry(
        context: Context,
        target: ExportTarget,
        isVideo: Boolean,
        write: (OutputStream) -> Unit,
    ): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, target.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath.trimEnd('/') + "/")
                if (!isVideo) {
                    put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis())
                }
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = try {
                context.contentResolver.insert(collection, values)
            } catch (e: Exception) {
                null
            } ?: return null

            var ok = false
            try {
                context.contentResolver.openOutputStream(uri)?.use { stream ->
                    write(stream)
                    ok = true
                }
            } catch (e: Throwable) {
                ok = false
            }
            if (!ok) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                return null
            }
            runCatching {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(uri, done, null, null)
            }
            return uri
        }

        // Android 9 及以下：直写原目录 + 媒体扫描
        val dirPath = target.legacyDir
            ?: File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                "Camera",
            ).absolutePath
        val dir = File(dirPath)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, target.displayName)
        return try {
            FileOutputStream(file).use { stream -> write(stream) }
            file.setLastModified(System.currentTimeMillis())
            awaitScan(context, file.absolutePath, target.mimeType) ?: Uri.fromFile(file)
        } catch (e: Throwable) {
            file.delete()
            null
        }
    }

    /** 等待媒体扫描回调（超时则返回 null，由调用方回退文件 Uri） */
    private fun awaitScan(context: Context, path: String, mime: String): Uri? {
        return try {
            val latch = CountDownLatch(1)
            var result: Uri? = null
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf(mime)) { _, uri ->
                result = uri
                latch.countDown()
            }
            latch.await(2, TimeUnit.SECONDS)
            result
        } catch (e: Throwable) {
            null
        }
    }
}
