package com.nuvio.app.features.library

import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.profiles.ProfilePinState
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** In-memory stand-in for the device storage so tests never touch real settings. */
internal class InMemoryHiddenStore : HiddenLibraryStore {
    val payloads = mutableMapOf<Int, String>()
    override fun load(profileIndex: Int): String? = payloads[profileIndex]
    override fun save(profileIndex: Int, payload: String) { payloads[profileIndex] = payload }
    override fun remove(profileIndex: Int) { payloads.remove(profileIndex) }
}

/** Points the repository at fakes for one test and restores the real collaborators afterwards. */
internal class HiddenRepositoryFixture {
    private val originalStore = LibraryHiddenRepository.store
    private val originalProfile = LibraryHiddenRepository.activeProfile
    private val originalPinState = LibraryHiddenRepository.profilePinState
    private val originalMatcher = LibraryHiddenRepository.matchesProfilePin

    val store = InMemoryHiddenStore()
    var profile = 1
    var profilePinState = ProfilePinState.NotSet
    var profilePin: String? = null

    fun install() {
        LibraryHiddenRepository.store = store
        LibraryHiddenRepository.activeProfile = { profile }
        LibraryHiddenRepository.profilePinState = { profilePinState }
        LibraryHiddenRepository.matchesProfilePin = { _, pin -> pin == profilePin }
        LibraryHiddenRepository.clearLocalState()
    }

    fun restore() {
        LibraryHiddenRepository.store = originalStore
        LibraryHiddenRepository.activeProfile = originalProfile
        LibraryHiddenRepository.profilePinState = originalPinState
        LibraryHiddenRepository.matchesProfilePin = originalMatcher
        LibraryHiddenRepository.clearLocalState()
    }
}

class LibraryHiddenRepositoryTest {
    private val fixture = HiddenRepositoryFixture()
    private val repo = LibraryHiddenRepository
    private val preview = MetaPreview(id = "tt42", type = "series", name = "Secret Show", poster = "p.jpg")

    @BeforeTest
    fun setUp() = fixture.install()

    @AfterTest
    fun tearDown() = fixture.restore()

    @Test
    fun theListAsksForThePinOnEveryVisit() {
        assertFalse(repo.uiState.value.pinEnabled)
        assertNull(repo.setPin("2468", "2468"))
        assertTrue(repo.uiState.value.pinEnabled)
        assertFalse(repo.uiState.value.unlocked, "setting the PIN does not open the list")

        assertFalse(repo.unlock("1111"))
        assertFalse(repo.uiState.value.unlocked)
        assertTrue(repo.unlock("2468"))
        assertTrue(repo.uiState.value.unlocked)

        repo.lock()
        assertFalse(repo.uiState.value.unlocked, "leaving the list locks it")
        // A fresh load (e.g. after switching profiles back) is locked too.
        repo.onProfileChanged()
        assertFalse(repo.uiState.value.unlocked)
        assertTrue(repo.uiState.value.pinEnabled)
    }

    @Test
    fun hiddenPinMustDifferFromTheProfilePin() {
        fixture.profilePinState = ProfilePinState.Cached
        fixture.profilePin = "1234"
        assertEquals(HiddenPinError.SameAsProfilePin, repo.setPin("1234", "1234"))
        assertNull(repo.setPin("2468", "2468"))

        // No local hash of the profile PIN: setup refuses until it has been confirmed once.
        repo.clearLocalState()
        fixture.store.payloads.clear()
        fixture.profilePinState = ProfilePinState.NotCached
        assertEquals(HiddenPinError.ProfilePinUnverified, repo.setPin("2468", "2468"))
        assertFalse(repo.isPinEnabled())
    }

    @Test
    fun pinRulesAndChangingThePin() {
        assertEquals(HiddenPinError.InvalidFormat, repo.setPin("12", "12"))
        assertEquals(HiddenPinError.Mismatch, repo.setPin("2468", "2469"))
        assertNull(repo.setPin("2468", "2468"))

        assertEquals(HiddenPinError.WrongPin, repo.setPin("1357", "1357", currentPin = "0000"))
        assertEquals(HiddenPinError.WrongPin, repo.setPin("1357", "1357", currentPin = null))
        assertNull(repo.setPin("1357", "1357", currentPin = "2468"))
        assertFalse(repo.unlock("2468"))
        assertTrue(repo.unlock("1357"))
    }

    @Test
    fun anyContentTypeCanBeHiddenAndRestored() {
        assertFalse(repo.hide(preview), "hiding needs the Hidden list to be set up")
        repo.setPin("2468", "2468")

        assertTrue(repo.hide(preview, nowEpochMs = 5L))
        assertTrue(repo.hide(MetaPreview(id = "tt7", type = "movie", name = "Film")))
        assertTrue(repo.hide(MetaPreview(id = "kitsu:9", type = "anime", name = "Anime")))
        assertTrue(repo.isHidden("series", "tt42"))
        assertTrue(repo.isHidden("film", "tt7"), "movie aliases share one key")
        assertTrue(repo.isHidden("anime", "kitsu:9"))
        assertEquals(3, repo.uiState.value.items.size)

        repo.unhide("series", "tt42")
        assertFalse(repo.isHidden("series", "tt42"))
        assertEquals(2, repo.uiState.value.items.size)

        // Persisted: a reload sees the same titles.
        repo.clearLocalState()
        assertTrue(repo.isHidden("movie", "tt7"))
    }

    @Test
    fun turningOffNeedsThePinAndReturnsTitlesToTheLists() {
        repo.setPin("2468", "2468")
        repo.hide(preview)

        assertEquals(HiddenPinError.WrongPin, repo.disable("0000"))
        assertTrue(repo.isHidden("series", "tt42"))

        assertNull(repo.disable("2468"))
        assertFalse(repo.uiState.value.pinEnabled)
        assertFalse(repo.isHidden("series", "tt42"))
    }

    @Test
    fun eachProfileHasItsOwnHiddenList() {
        repo.setPin("2468", "2468")
        repo.hide(preview)

        fixture.profile = 2
        repo.onProfileChanged()
        assertFalse(repo.uiState.value.pinEnabled)
        assertFalse(repo.isHidden("series", "tt42"))
        assertNull(repo.setPin("1357", "1357", profileIndex = 2))
        assertFalse(repo.unlock("2468"), "profile 1's PIN does not open profile 2's list")

        fixture.profile = 1
        repo.onProfileChanged()
        assertTrue(repo.isHidden("series", "tt42"))
        assertTrue(repo.unlock("2468"))
    }

    @Test
    fun hiddenTitlesAreDrawnOnlyWhileUnlocked() {
        repo.setPin("2468", "2468")
        repo.hide(preview)
        assertFalse(showsHiddenTitles(LibraryViewMode.Hidden, repo.uiState.value), "locked: nothing is drawn")
        assertTrue(repo.unlock("2468"))
        assertTrue(showsHiddenTitles(LibraryViewMode.Hidden, repo.uiState.value))
        assertFalse(showsHiddenTitles(LibraryViewMode.Saved, repo.uiState.value))
        repo.lock()
        assertFalse(showsHiddenTitles(LibraryViewMode.Hidden, repo.uiState.value), "re-locked: hidden again at once")
    }
}
