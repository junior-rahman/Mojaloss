package com.mojaloss.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.mojaloss.core.model.SessionState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Real Application/Activity integration with a clean signed-out install; never submits auth. */
@UnstableApi
class MainActivityStartupTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun actualAppStartsAndRecreatesWithEditableProviderAddress() {
        val application = ApplicationProvider.getApplicationContext<MojaLossApplication>()
        // Abort before launching if a saved session could trigger a real provider request.
        assertFalse("This offline startup test requires a clean install",
            File(application.noBackupFilesDir, "mojaloss/session.enc").exists())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            assertConnectionForm(application)
            scenario.recreate()
            assertConnectionForm(application)
        }
    }

    private fun assertConnectionForm(application: MojaLossApplication) {
        compose.onNodeWithText("Server URL").performScrollTo().assertIsDisplayed()
            .assertIsEnabled().assertTextContains("https://www.mojaloss.stream/jelly")
        compose.onNodeWithText("Use Quick Connect").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(SessionState.SignedOut, application.repository.session.value) }
    }
}
