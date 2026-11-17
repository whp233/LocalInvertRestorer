package android.graphics

/**
 * 仅用于 JVM 端验证的最小 Bitmap 替身。
 *
 * 目的是在电脑上直接跑 `com.invrestore.core` 里的检测器，确认 Kotlin 版
 * 与 tools/invrestore_ref.py 的结果一致，不需要模拟器。
 * App 本身编译时用的是真正的 android.graphics.Bitmap。
 */
class Bitmap private constructor(
    val width: Int,
    val height: Int,
    private val pixels: IntArray
) {

    enum class Config { ARGB_8888, RGB_565 }

    fun getPixels(out: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (row in 0 until h) {
            System.arraycopy(pixels, (y + row) * width + x, out, offset + row * stride, w)
        }
    }

    fun setPixels(p: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        for (row in 0 until h) {
            System.arraycopy(p, offset + row * stride, pixels, (y + row) * width + x, w)
        }
    }

    fun copy(config: Config, mutable: Boolean): Bitmap = Bitmap(width, height, pixels.copyOf())

    fun recycle() { }

    companion object {
        fun create(w: Int, h: Int, p: IntArray) = Bitmap(w, h, p)

        /** 与 Android 的双线性缩放不同，这里用最近邻，足够验证检测逻辑。 */
        fun createScaledBitmap(src: Bitmap, w: Int, h: Int, filter: Boolean): Bitmap {
            val out = IntArray(w * h)
            val tmp = IntArray(src.width * src.height)
            src.getPixels(tmp, 0, src.width, 0, 0, src.width, src.height)
            for (y in 0 until h) {
                val sy = (y.toLong() * src.height / h).toInt()
                for (x in 0 until w) {
                    val sx = (x.toLong() * src.width / w).toInt()
                    out[y * w + x] = tmp[sy * src.width + sx]
                }
            }
            return Bitmap(w, h, out)
        }
    }
}
