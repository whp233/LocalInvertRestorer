package com.invrestore

import android.net.Uri
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import com.invrestore.data.MainViewModel
import com.invrestore.databinding.ActivityMainBinding
import com.invrestore.ui.BatchFragment
import com.invrestore.ui.EditorFragment
import com.invrestore.ui.InvertFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var currentTab = R.id.nav_restore

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.bottomNav.selectedItemId != R.id.nav_restore) {
                binding.bottomNav.selectedItemId = R.id.nav_restore
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.container.updatePadding(
                left = bars.left, top = bars.top, right = bars.right
            )
            binding.bottomNav.updatePadding(
                left = bars.left, right = bars.right, bottom = bars.bottom
            )
            insets
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            switchTo(item.itemId)
            true
        }
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (savedInstanceState == null) {
            switchTo(R.id.nav_restore)
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.nav_restore)
            binding.bottomNav.selectedItemId = currentTab
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_TAB, currentTab)
        super.onSaveInstanceState(outState)
    }

    /** 供批量详情跳转到单张页继续处理。 */
    fun openEditor(uri: Uri) {
        viewModel.openEditor(uri)
        binding.bottomNav.selectedItemId = R.id.nav_restore
    }

    private fun switchTo(itemId: Int) {
        val tag = when (itemId) {
            R.id.nav_batch -> TAG_BATCH
            R.id.nav_invert -> TAG_INVERT
            else -> TAG_EDITOR
        }
        val fm = supportFragmentManager
        val existing = fm.fragments.firstOrNull { it.tag == tag }
        // commitNow：页签切换是同步的，否则快速连点可能把同一个页签 add 两次。
        val transaction = fm.beginTransaction()

        val target: Fragment = existing ?: when (tag) {
            TAG_BATCH -> BatchFragment()
            TAG_INVERT -> InvertFragment()
            else -> EditorFragment()
        }
        if (existing == null) transaction.add(R.id.container, target, tag)

        for (fragment in fm.fragments) {
            if (fragment !== target && !fragment.isHidden) transaction.hide(fragment)
        }
        if (existing != null && existing.isHidden) transaction.show(existing)

        if (fm.isStateSaved) transaction.commitAllowingStateLoss() else transaction.commitNow()
        currentTab = itemId
    }

    companion object {
        private const val TAG_EDITOR = "editor"
        private const val TAG_BATCH = "batch"
        private const val TAG_INVERT = "invert"
        private const val KEY_TAB = "tab"
    }
}
