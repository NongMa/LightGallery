package com.liu.gallery.data

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** 附加属性（EXIF / 视频元数据），字段均可能为空 */
data class ExtraInfo(
    /** 文件实际字节数：当 MediaStore 的 size 列不可用时由文件描述符兜底 */
    val fileSize: Long = 0L,
    val make: String? = null,
    val model: String? = null,
    val lens: String? = null,
    val fNumber: String? = null,
    val exposure: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val software: String? = null,
    val dateTimeOriginal: String? = null,
    val gps: String? = null,
    val videoDate: String? = null,
    val videoGps: String? = null,
)

/** 属性查看用：读取图片 EXIF 与视频元数据（全部在 IO 线程） */
object InfoLoader {

    suspend fun load(context: Context, item: MediaItem): ExtraInfo = withContext(Dispatchers.IO) {
        val base = if (item.isVideo) loadVideo(context, item) else loadExif(context, item)
        // size 列可能被 ROM 拒绝，此时从文件描述符读取真实字节数
        val size = if (item.sizeBytes > 0) item.sizeBytes else fileSizeOf(context, item)
        base.copy(fileSize = size)
    }

    private fun fileSizeOf(context: Context, item: MediaItem): Long = try {
        context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
            pfd.statSize.takeIf { it > 0 } ?: 0L
        } ?: 0L
    } catch (e: Exception) {
        0L
    }

    // ------------------------------------------------------------- 图片 EXIF

    private fun loadExif(context: Context, item: MediaItem): ExtraInfo {
        val stream = try {
            context.contentResolver.openInputStream(item.uri)
        } catch (e: Exception) {
            null
        } ?: return ExtraInfo()

        val exif = stream.use { input ->
            try {
                ExifInterface(input)
            } catch (e: Exception) {
                null
            }
        } ?: return ExtraInfo()

        fun attr(vararg keys: String): String? {
            for (key in keys) {
                try {
                    val value = exif.getAttribute(key)
                    if (!value.isNullOrBlank() &&
                        !value.startsWith("unknown", ignoreCase = true)
                    ) {
                        return value
                    }
                } catch (e: Exception) {
                    // 忽略单个标签的读取失败
                }
            }
            return null
        }

        val make = attr(ExifInterface.TAG_MAKE)
        val model = attr(ExifInterface.TAG_MODEL)
        val fNumberRaw = try {
            exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
        } catch (e: Exception) {
            -1.0
        }
        val exposureRaw = try {
            exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
        } catch (e: Exception) {
            -1.0
        }
        val isoRaw = (try {
            exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1)
        } catch (e: Exception) {
            -1
        }).takeIf { it > 0 } ?: try {
            exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, -1)
        } catch (e: Exception) {
            -1
        }
        val focalRaw = try {
            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
        } catch (e: Exception) {
            -1.0
        }

        return ExtraInfo(
            make = make,
            model = model,
            lens = attr(ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_MAKE),
            fNumber = if (fNumberRaw > 0) "f/" + trimZero(fNumberRaw) else null,
            exposure = formatExposure(exposureRaw),
            iso = if (isoRaw > 0) isoRaw.toString() else null,
            focalLength = if (focalRaw > 0) trimZero(focalRaw) + " mm" else null,
            software = attr(ExifInterface.TAG_SOFTWARE),
            dateTimeOriginal = attr(
                ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_DATETIME
            ),
            gps = readGps(exif),
        )
    }

    private fun readGps(exif: ExifInterface): String? = try {
        val latRaw = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) ?: return null
        val latRef = exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF) ?: "N"
        val lonRaw = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE) ?: return null
        val lonRef = exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF) ?: "E"
        val lat = toDecimalDegrees(latRaw) ?: return null
        val lon = toDecimalDegrees(lonRaw) ?: return null
        val latVal = if (latRef.startsWith("S")) -lat else lat
        val lonVal = if (lonRef.startsWith("W")) -lon else lon
        String.format(Locale.CHINA, "%.5f, %.5f", latVal, lonVal)
    } catch (e: Exception) {
        null
    }

    /** "40/1,26/1,33/1" → 40.4425 */
    private fun toDecimalDegrees(rational: String): Double? {
        val parts = rational.split(",")
        if (parts.size < 3) return null
        fun toDouble(s: String): Double? {
            val seg = s.trim().split("/")
            return try {
                if (seg.size == 2) seg[0].toDouble() / seg[1].toDouble().coerceAtLeast(1e-9)
                else seg[0].toDouble()
            } catch (e: Exception) {
                null
            }
        }
        val d = toDouble(parts[0]) ?: return null
        val m = toDouble(parts[1]) ?: return null
        val s = toDouble(parts[2]) ?: return null
        return d + m / 60.0 + s / 3600.0
    }

    private fun formatExposure(seconds: Double): String? {
        if (seconds <= 0) return null
        return if (seconds < 1.0) {
            val denom = (1.0 / seconds).toInt().coerceAtLeast(1)
            "1/$denom 秒"
        } else {
            trimZero(seconds) + " 秒"
        }
    }

    private fun trimZero(value: Double): String {
        val s = String.format(Locale.CHINA, "%.1f", value)
        return s.removeSuffix(".0")
    }

    // ------------------------------------------------------------- 视频元数据

    private fun loadVideo(context: Context, item: MediaItem): ExtraInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            // 优先走 content URI（分区存储下仍可读）
            try {
                retriever.setDataSource(context, item.uri)
            } catch (e: Exception) {
                retriever.setDataSource(item.uri.path ?: return ExtraInfo())
            }
            fun meta(key: Int): String? = try {
                retriever.extractMetadata(key)?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
            ExtraInfo(
                videoDate = meta(MediaMetadataRetriever.METADATA_KEY_DATE),
                videoGps = meta(MediaMetadataRetriever.METADATA_KEY_LOCATION),
                software = meta(MediaMetadataRetriever.METADATA_KEY_TITLE),
            )
        } catch (e: Exception) {
            ExtraInfo()
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
            }
        }
    }
}
