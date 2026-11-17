package harness

import android.graphics.Bitmap
import com.invrestore.core.Candidate
import com.invrestore.core.InversionDetector
import com.invrestore.core.PatchKind
import com.invrestore.core.Restorer
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt

private fun load(path: String): Bitmap {
    val img: BufferedImage = ImageIO.read(File(path))
    val w = img.width
    val h = img.height
    val px = IntArray(w * h)
    img.getRGB(0, 0, w, h, px, 0, w)
    return Bitmap.create(w, h, px)
}

private fun save(bmp: Bitmap, path: String) {
    val px = IntArray(bmp.width * bmp.height)
    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val img = BufferedImage(bmp.width, bmp.height, BufferedImage.TYPE_INT_RGB)
    img.setRGB(0, 0, bmp.width, bmp.height, px, 0, bmp.width)
    ImageIO.write(img, "png", File(path))
}

fun main(args: Array<String>) {
    val input = args.getOrNull(0) ?: error("usage: run --args=\"image.png [outdir]\"")
    val outDir = args.getOrNull(1) ?: "."
    val src = load(input)

    val t0 = System.currentTimeMillis()
    val found = InversionDetector().detect(src)
    val ms = System.currentTimeMillis() - t0

    println("image ${src.width}x${src.height}  detect=${ms}ms  candidates=${found.size}")
    found.forEach { c ->
        println("  [${c.kind}] ${c.restoreSides}  mask=${c.mask} " +
                "cost ${"%.3f".format(c.costBefore)} -> ${"%.3f".format(c.costAfter)} " +
                "conf=${(c.confidence * 100).roundToInt()}%")
    }

    val best: Candidate? = found.firstOrNull { it.kind == PatchKind.INVERSION }
    if (best != null) {
        val out = Restorer.restore(src, best.rect, best.mask)
        save(out, File(outDir, "jvm_restored.png").path)
        println("restored -> ${File(outDir, "jvm_restored.png").path}")
    }
}
