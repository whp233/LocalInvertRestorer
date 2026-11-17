package com.invrestore

import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.invrestore.ui.EditorFragment
import com.invrestore.ui.PhotoCanvasView
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 冒烟测试：主题、布局、自定义 View、Fragment 容器都要能正常加载。
 * 这一层出错会直接崩溃，而 JVM 上的算法测试覆盖不到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SmokeTest {

    @Test
    fun mainActivityShowsEditor() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        assertNotNull(activity.findViewById<BottomNavigationView>(R.id.bottomNav))
        assertNotNull(activity.findViewById<RecyclerView>(R.id.candidateList))

        val fragment = activity.supportFragmentManager.fragments
            .filterIsInstance<EditorFragment>().firstOrNull()
        assertNotNull(fragment)
        assertNotNull(fragment?.view?.findViewById<PhotoCanvasView>(R.id.canvas))
    }
}
