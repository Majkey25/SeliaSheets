package com.majkeylab.seliadocs

import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.majkeylab.seliadocs.settings.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemBarAppearanceTest {
    val rule = createAndroidComposeRule<MainActivity>()
    @get:Rule val appReady = com.majkeylab.seliadocs.readyAppRule(rule)

    @Test
    fun startupPreservesSystemBarContrastAndCutout() {
        rule.runOnIdle {
            val window = rule.activity.window
            assertTrue(window.isNavigationBarContrastEnforced)
            assertFalse(window.isStatusBarContrastEnforced)
            assertEquals(
                if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES,
                window.attributes.layoutInDisplayCutoutMode,
            )
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun manualThemeTransitionsUpdateSystemBarIconsIndependentOfSystemTheme() {
        lateinit var theme: MutableState<AppTheme>
        rule.activity.setContent {
            theme = remember { mutableStateOf(AppTheme.LIGHT) }
            theme.value.resolveDarkTheme(
                activity = LocalActivity.current as? MainActivity,
                systemDarkTheme = true,
            )
        }

        assertSystemBarIconsAreDark()
        rule.runOnIdle { theme.value = AppTheme.DARK }
        rule.waitForIdle()
        assertSystemBarIconsAreLight()
        rule.runOnIdle { theme.value = AppTheme.LIGHT }
        rule.waitForIdle()
        assertSystemBarIconsAreDark()
    }

    private fun assertSystemBarIconsAreDark() {
        rule.runOnIdle {
            val controller =
                WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)

            assertTrue(controller.isAppearanceLightStatusBars)
            assertTrue(controller.isAppearanceLightNavigationBars)
        }
    }

    private fun assertSystemBarIconsAreLight() {
        rule.runOnIdle {
            val controller =
                WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView)

            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
    }
}
