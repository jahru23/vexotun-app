package com.vexotun.app.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vexotun.app.model.TunnelMode
import com.vexotun.app.ui.theme.VexotunTheme
import com.vexotun.app.vpn.ConnectionStatus
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** UI tests for core composables in [com.vexotun.app.ui.main]. */
class MainScreenTest {

    @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setup() {
        composeTestRule.setContent {
            VexotunTheme(darkTheme = true) {
                ModeBadge(TunnelMode.XRAY)
                StatusBadge(ConnectionStatus.CONNECTED)
            }
        }
    }

    @Test
    fun badgesRenderExpectedLabels() {
        composeTestRule.onNodeWithText("Xray").assertExists()
        composeTestRule.onNodeWithText("Terhubung").assertExists()
    }
}
