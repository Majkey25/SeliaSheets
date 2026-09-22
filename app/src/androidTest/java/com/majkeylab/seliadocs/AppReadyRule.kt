package com.majkeylab.seliadocs

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement

/** Wait after activity/Compose setup, before test setup changes the screen or settings. */
internal fun readyAppRule(rule: ComposeContentTestRule): TestRule =
    RuleChain.outerRule(rule).around(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                // DataStore's first emission is not covered by Compose's idle detection.
                rule.waitUntil(10_000) {
                    runCatching {
                        rule.onNodeWithContentDescription("New notebook")
                            .assertIsDisplayed().assertIsEnabled().assertHasClickAction()
                    }.isSuccess
                }
                base.evaluate()
            }
        }
    })
