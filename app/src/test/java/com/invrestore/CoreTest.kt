package com.invrestore

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.invrestore.core.ImageOps
import com.invrestore.core.InversionDetector
import com.invrestore.core.PatchKind
import com.invrestore.core.RectI
import com.invrestore.core.RestorePipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 核心算法回归：真实样张检测、批量还原、负样本、整图/框选反色。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreTest {

    private fun demo(): Bitmap = BitmapFactory.decodeFile(
        TestPaths.sample("demo_inverted.png").absolutePath
    ) ?: error("sample missing")

    @Test
    fun detectsBothInvertedBlocks() {
        val found = InversionDetector().detect(demo())
        val inversions = found.filter { it.kind == PatchKind.INVERSION }
        assertEquals(2, inversions.size)
        assertTrue(inversions.all { it.confidence > 0.7f })
        // 已知真值：左下大块 (380,930)-(700,1140)
        val hit = inversions.firstOrNull {
            kotlin.math.abs(it.rect.l - 380) <= 6 && kotlin.math.abs(it.rect.t - 930) <= 6
        }
        assertNotNull("known region not found: ${found.map { it.restoreSides }}", hit)
    }

    @Test
    fun batchRestoreHandlesAllRegions() {
        val src = demo()
        val found = InversionDetector().detect(src)
        val result = RestorePipeline.restoreAll(src, found)
        assertNotNull(result.bitmap)
        assertEquals(2, result.applied.size)
        assertEquals(result.applied.size, result.applied.map { it.rect }.distinct().size)
    }

    @Test
    fun restoredImageProducesNoInversionCandidate() {
        val src = demo()
        val found = InversionDetector().detect(src)
        val restored = RestorePipeline.restoreAll(src, found).bitmap!!
        val again = InversionDetector().detect(restored)
        assertEquals(emptyList<PatchKind>(), again.filter { it.kind == PatchKind.INVERSION }
            .map { it.kind })
    }

    @Test
    fun invertWholeImageFlipsEveryPixel() {
        val src = demo()
        val out = ImageOps.invert(src)
        assertEquals(src.width, out.width)
        assertEquals(src.height, out.height)
        assertEquals(flip(src.getPixel(10, 10)), out.getPixel(10, 10))
    }

    private fun flip(c: Int): Int {
        val a = (c ushr 24) and 0xFF
        val r = 255 - ((c shr 16) and 0xFF)
        val g = 255 - ((c shr 8) and 0xFF)
        val b = 255 - (c and 0xFF)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun invertRegionOnlyTouchesRect() {
        val src = demo()
        val rect = RectI(100, 100, 200, 200)
        val out = ImageOps.invert(src, rect)
        val outside = 20 to 20
        val inside = 150 to 150
        assertEquals(src.getPixel(outside.first, outside.second), out.getPixel(outside.first, outside.second))
        assertEquals(flip(src.getPixel(inside.first, inside.second)),
            out.getPixel(inside.first, inside.second))

    }
}
