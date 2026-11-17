package com.invrestore

import android.widget.Button
import android.widget.ImageView
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冒烟测试：确认主题、布局、自定义 View 都能正常加载 —— 这一层出错会直接崩溃，
 * 而 JVM 上的算法测试覆盖不到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmokeTest {

    @Test
    fun mainActivityInflates() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(activity.findViewById<ImageView>(R.id.preview))
        assertNotNull(activity.findViewById<Button>(R.id.btnDetect))
        assertNotNull(activity.findViewById<com.invrestore.ui.RegionOverlayView>(R.id.overlay))
    }
}
