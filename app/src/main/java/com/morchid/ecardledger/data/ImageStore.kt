package com.morchid.ecardledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 图片存取：资产页顶部轮换图 + 用户头像。
 *
 * ## 引用格式
 *
 * 统一用带前缀的字符串，这样「预置图」和「用户传的图」能放在同一个列表里：
 *  - `asset:aiwan1.jpg` —— 打包在 APK 里的预置风景图
 *  - `file:/data/.../images/xxx.jpg` —— 用户选的图，**已经复制进 App 私有目录**
 *
 * 为什么一定要复制进来：用户从相册选的 uri 是一次性授权，重启后就失效了；
 * 而且原图动辄几 MB / 8000px 宽，直接存原图既费空间又容易 OOM。
 * 所以导入时**降采样后重新编码**，落一张最长边 1600px 的 JPEG。
 */
object ImageStore {

    private const val MAX_IMPORT_EDGE = 1600
    private const val JPEG_QUALITY = 85
    private const val TAG = "ImageStore"

    fun assetName(ref: String): String? =
        ref.removePrefix("asset:").takeIf { ref.startsWith("asset:") }

    fun filePath(ref: String): String? =
        ref.removePrefix("file:").takeIf { ref.startsWith("file:") }

    /**
     * 把用户选的图片收进 App 私有目录。
     * @return `file:` 引用；失败返回 null
     */
    fun importFromUri(context: Context, uri: Uri, prefix: String): String? = runCatching {
        val dir = File(context.filesDir, "images").apply { mkdirs() }
        // 先量一下尺寸，决定采样率 —— 直接读原图在 8000px 的照片上必 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_IMPORT_EDGE)
        }
        val bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        // 再把长边压到 1600（inSampleSize 只能按 2 的幂降，可能还偏大）
        val scaled = scaleDown(bitmap, MAX_IMPORT_EDGE)
        val target = File(dir, "$prefix-${System.currentTimeMillis()}.jpg")
        FileOutputStream(target).use { scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (scaled !== bitmap) bitmap.recycle()
        scaled.recycle()
        "file:${target.absolutePath}"
    }.getOrNull()

    /**
     * 解码结果缓存。
     *
     * 三个页面顶部用的是同一张图，之前各解一遍（一张 900px 位图 ≈ 3.6MB）；
     * 6 秒轮换时还会再解一次。滑动掉帧有很大一部分来自这些重复解码 + 纹理上传。
     * 只留最近 6 张，够覆盖「当前 + 下一张 + 头像」。
     */
    private val cache = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean =
                size > 6
        },
    )

    /** 取（带缓存）解码结果；失败返回 null 并打日志，方便排查"图怎么没出来" */
    fun load(context: Context, ref: String, targetWidthPx: Int): Bitmap? {
        val key = "$ref@$targetWidthPx"
        cache[key]?.let { if (!it.isRecycled) return it }
        val decoded = decode(context, ref, targetWidthPx)
        if (decoded != null) cache[key] = decoded
        return decoded
    }

    private fun decode(context: Context, ref: String, targetWidthPx: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            when {
                assetName(ref) != null -> context.assets.open(assetName(ref)!!)
                    .use { BitmapFactory.decodeStream(it, null, options) }
                filePath(ref) != null -> BitmapFactory.decodeFile(filePath(ref), options)
                else -> {
                    Log.w(TAG, "引用格式不认识：$ref")
                    return null
                }
            }
            if (options.outWidth <= 0) {
                Log.w(TAG, "量不出尺寸（文件不存在或不是图片）：$ref")
                return null
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(options.outWidth, options.outHeight, targetWidthPx)
            }
            val bitmap = when {
                assetName(ref) != null -> context.assets.open(assetName(ref)!!)
                    .use { BitmapFactory.decodeStream(it, null, decodeOptions) }
                else -> BitmapFactory.decodeFile(filePath(ref), decodeOptions)
            }
            if (bitmap == null) {
                Log.w(TAG, "解码返回 null：$ref (${options.outWidth}x${options.outHeight})")
            } else {
                Log.i(TAG, "解码成功：$ref → ${bitmap.width}x${bitmap.height}")
            }
            bitmap
        } catch (t: Throwable) {
            Log.w(TAG, "解码异常：$ref", t)
            null
        }
    }

    /** 只量尺寸，不解码像素（列表里判断「这张还能不能用」时用） */
    fun exists(context: Context, ref: String): Boolean = runCatching {
        when {
            assetName(ref) != null -> context.assets.open(assetName(ref)!!).use { true }
            filePath(ref) != null -> File(filePath(ref)!!).exists()
            else -> false
        }
    }.getOrDefault(false)

    /** 2 的幂采样率：让解码后的宽度不小于 target 的一半 */
    internal fun sampleSizeFor(width: Int, height: Int, target: Int): Int {
        if (target <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= target) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}
