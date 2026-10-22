package com.example.bluetooth_messenger.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.WindowManager
import org.junit.Before
import com.example.bluetooth_messenger.ui.auth.PinScreen
import com.example.bluetooth_messenger.ui.theme.BluetoothmessengerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PinScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun keepScreenAwake() {
        compose.activity.runOnUiThread {
            compose.activity.setTurnScreenOn(true)
            compose.activity.setShowWhenLocked(true)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    @Test fun setupRequiresMatchingSixDigitPinsAndClearsSubmittedInput() {
        var submitted: String? = null
        compose.setContent { BluetoothmessengerTheme { PinScreen(true, { submitted = it }, {}) } }
        val submit = compose.onNodeWithText("Создать защищённый профиль")
        submit.assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("123456")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("654321")
        submit.assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("123456")
        submit.assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("123456", submitted) }
        compose.onAllNodes(hasSetTextAction())[0].assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
    @Test fun unlockDoesNotAcceptIncompletePin() {
        compose.setContent { BluetoothmessengerTheme { PinScreen(false, {}, {}) } }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("12345")
        compose.onNodeWithText("Войти").assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("6")
        compose.onNodeWithText("Войти").assertIsEnabled()
    }
}
