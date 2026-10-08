package com.nuvio.app.features.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.LocalScreenActive
import org.junit.After
import org.junit.Before
import org.junit.Rule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryHiddenUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixture = HiddenRepositoryFixture()

    @Before
    fun setUp() {
        fixture.install()
        LibraryHiddenRepository.setPin("2468", "2468")
    }

    @After
    fun tearDown() = fixture.restore()

    private fun enterPin(pin: String) {
        pin.forEach { digit -> compose.onNodeWithText(digit.toString()).performClick() }
        compose.waitForIdle()
    }

    @Test
    fun posterRemoveButtonRemovesWithoutOpeningTheTitle() {
        var removed = 0
        var opened = 0
        compose.setContent {
            Box(Modifier.size(180.dp, 270.dp).testTag("poster").clickable { opened++ }) {
                LibraryPosterOverlay(onRemove = { removed++ }, progressFraction = 0.45f)
            }
        }

        compose.onNodeWithContentDescription("Remove").performClick()

        compose.runOnIdle {
            assertEquals(1, removed)
            assertEquals(0, opened, "the × must not also open the title")
        }
    }

    @Test
    fun unlockDialogAcceptsOnlyTheHiddenPin() {
        var unlocked = 0
        compose.setContent { HiddenUnlockDialog(onUnlocked = { unlocked++ }, onDismiss = {}) }

        enterPin("1111")
        compose.onNodeWithText("Incorrect PIN.").assertExists()
        compose.runOnIdle {
            assertEquals(0, unlocked)
            assertFalse(LibraryHiddenRepository.uiState.value.unlocked)
        }

        enterPin("2468")
        compose.runOnIdle {
            assertEquals(1, unlocked)
            assertTrue(LibraryHiddenRepository.uiState.value.unlocked)
        }
    }

    @Test
    fun leavingTheScreenLocksTheList() {
        assertTrue(LibraryHiddenRepository.unlock("2468"))
        var screenActive by mutableStateOf(true)
        var lockedCallbacks = 0
        compose.setContent {
            CompositionLocalProvider(LocalScreenActive provides screenActive) {
                HiddenListAutoLock(open = true, onLocked = { lockedCallbacks++ })
            }
        }
        compose.runOnIdle { assertTrue(LibraryHiddenRepository.uiState.value.unlocked) }

        // Another app tab / details page / player takes over.
        screenActive = false
        compose.runOnIdle {
            assertFalse(LibraryHiddenRepository.uiState.value.unlocked)
            assertEquals(1, lockedCallbacks)
        }
    }

    @Test
    fun switchingAwayFromTheHiddenTabLocksTheList() {
        assertTrue(LibraryHiddenRepository.unlock("2468"))
        var open by mutableStateOf(true)
        compose.setContent { HiddenListAutoLock(open = open, onLocked = {}) }
        compose.runOnIdle { assertTrue(LibraryHiddenRepository.uiState.value.unlocked) }

        // Saved / Cloud selected again.
        open = false
        compose.runOnIdle { assertFalse(LibraryHiddenRepository.uiState.value.unlocked) }

        // Coming back needs the PIN again.
        open = true
        compose.runOnIdle { assertFalse(LibraryHiddenRepository.uiState.value.unlocked) }
    }

    @Test
    fun setupRejectsTheProfilePin() {
        fixture.store.payloads.clear()
        LibraryHiddenRepository.clearLocalState()
        fixture.profilePinState = com.nuvio.app.features.profiles.ProfilePinState.Cached
        fixture.profilePin = "1234"
        var done = 0
        compose.setContent { HiddenPinSetupDialog(profileIndex = fixture.profile, onDone = { done++ }, onDismiss = {}) }

        enterPin("1234")
        compose.onNodeWithText("Choose a PIN that's different from your profile PIN.").assertExists()

        enterPin("2468")   // new PIN
        enterPin("2468")   // confirm
        compose.runOnIdle {
            assertEquals(1, done)
            assertTrue(LibraryHiddenRepository.isPinEnabled(fixture.profile))
        }
    }
}
