package com.nuvio.app.features.library

import co.touchlab.kermit.Logger
import com.nuvio.app.features.home.MetaPreview
import com.nuvio.app.features.home.PosterShape
import com.nuvio.app.features.profiles.ProfilePinCrypto
import com.nuvio.app.features.profiles.ProfilePinState
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.profiles.generateProfilePinSalt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A title moved into the Hidden list; enough metadata to show its poster without the Library. */
@Serializable
internal data class HiddenLibraryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val banner: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val genres: List<String> = emptyList(),
    val hiddenAtEpochMs: Long = 0L,
) {
    val key: String get() = hiddenKey(type, id)

    fun toMetaPreview(): MetaPreview = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = poster,
        banner = banner,
        logo = logo,
        posterShape = PosterShape.Poster,
        description = description,
        releaseInfo = releaseInfo,
        genres = genres,
        rawPosterUrl = poster,
    )
}

internal data class LibraryHiddenUiState(
    val pinEnabled: Boolean = false,
    /** Unlocked for this visit only; never persisted, so every visit asks for the PIN. */
    val unlocked: Boolean = false,
    val items: List<HiddenLibraryItem> = emptyList(),
) {
    val hiddenKeys: Set<String> = items.mapTo(hashSetOf()) { it.key }

    fun isHidden(type: String, id: String): Boolean = hiddenKey(type, id) in hiddenKeys
}

internal enum class HiddenPinError {
    InvalidFormat,
    Mismatch,
    SameAsProfilePin,
    WrongPin,

    /** The profile PIN must be entered once on this device before the two can be compared. */
    ProfilePinUnverified,
}

/** Where the Hidden list keeps its data; swapped for an in-memory store in tests. */
internal interface HiddenLibraryStore {
    fun load(profileIndex: Int): String?
    fun save(profileIndex: Int, payload: String)
    fun remove(profileIndex: Int)
}

private object DeviceHiddenLibraryStore : HiddenLibraryStore {
    override fun load(profileIndex: Int): String? = LibraryHiddenStorage.loadPayload(profileIndex)
    override fun save(profileIndex: Int, payload: String) = LibraryHiddenStorage.savePayload(profileIndex, payload)
    override fun remove(profileIndex: Int) = LibraryHiddenStorage.removePayload(profileIndex)
}

internal fun hiddenKey(type: String, id: String): String =
    "${normalizedHiddenType(type)}:${id.trim()}"

/** Anime, series and other episodic types share one key space so hiding works from any surface. */
private fun normalizedHiddenType(type: String): String = when (type.trim().lowercase()) {
    "movie", "film" -> "movie"
    else -> type.trim().lowercase()
}

/** The Hidden list PIN: four digits, confirmed twice, and different from the profile's lock PIN. */
internal fun validateNewHiddenPin(pin: String, confirmation: String, matchesProfilePin: Boolean): HiddenPinError? = when {
    pin.length != HIDDEN_PIN_LENGTH || !pin.all(Char::isDigit) -> HiddenPinError.InvalidFormat
    pin != confirmation -> HiddenPinError.Mismatch
    matchesProfilePin -> HiddenPinError.SameAsProfilePin
    else -> null
}

internal const val HIDDEN_PIN_LENGTH = 4

internal fun hashHiddenPin(profileIndex: Int, salt: String, pin: String): String =
    ProfilePinCrypto.sha256Hex("library-hidden:$profileIndex:$salt:$pin")

/**
 * The Hidden list: titles moved here disappear from every regular Library list and the Library
 * calendar. It has its own PIN (never the profile PIN), asks for it on every visit and locks
 * again as soon as the list is left.
 */
internal object LibraryHiddenRepository {
    private val log = Logger.withTag("LibraryHidden")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val _uiState = MutableStateFlow(LibraryHiddenUiState())
    val uiState: StateFlow<LibraryHiddenUiState> = _uiState.asStateFlow()

    private var loadedProfileIndex: Int? = null
    private var stored = StoredHiddenLibrary()

    // Collaborators, replaceable in tests.
    internal var store: HiddenLibraryStore = DeviceHiddenLibraryStore
    internal var activeProfile: () -> Int = { ProfileRepository.activeProfileId }
    internal var profilePinState: (Int) -> ProfilePinState = ProfileRepository::profilePinState
    internal var matchesProfilePin: (Int, String) -> Boolean = ProfileRepository::matchesCachedProfilePin

    fun ensureLoaded() {
        val profileIndex = activeProfile()
        if (loadedProfileIndex == profileIndex) return
        load(profileIndex)
    }

    fun onProfileChanged() {
        load(activeProfile())
    }

    fun clearLocalState() {
        loadedProfileIndex = null
        stored = StoredHiddenLibrary()
        _uiState.value = LibraryHiddenUiState()
    }

    fun isPinEnabled(profileIndex: Int = activeProfile()): Boolean =
        read(profileIndex).digest != null

    /** Turns the Hidden list on (or changes its PIN). Changing requires the current PIN. */
    fun setPin(
        pin: String,
        confirmation: String,
        currentPin: String? = null,
        profileIndex: Int = activeProfile(),
    ): HiddenPinError? {
        ensureLoaded()
        val existing = read(profileIndex)
        if (existing.digest != null && (currentPin == null || !existing.matches(profileIndex, currentPin))) {
            return HiddenPinError.WrongPin
        }
        // The Hidden PIN must never equal the profile lock PIN; that needs a local hash to compare.
        if (profilePinState(profileIndex) == ProfilePinState.NotCached) return HiddenPinError.ProfilePinUnverified
        validateNewHiddenPin(
            pin = pin,
            confirmation = confirmation,
            matchesProfilePin = matchesProfilePin(profileIndex, pin),
        )?.let { return it }
        val salt = generateProfilePinSalt()
        write(profileIndex, existing.copy(salt = salt, digest = hashHiddenPin(profileIndex, salt, pin)))
        return null
    }

    /** Turns the Hidden list off; its titles return to the regular lists. */
    fun disable(pin: String, profileIndex: Int = activeProfile()): HiddenPinError? {
        ensureLoaded()
        val existing = read(profileIndex)
        if (existing.digest == null) return null
        if (!existing.matches(profileIndex, pin)) return HiddenPinError.WrongPin
        store.remove(profileIndex)
        if (loadedProfileIndex == profileIndex) load(profileIndex)
        return null
    }

    /** Checks the Hidden list PIN without unlocking (e.g. before changing it). */
    fun verifyPin(pin: String, profileIndex: Int = activeProfile()): Boolean =
        read(profileIndex).matches(profileIndex, pin)

    /** Unlocks the Hidden list for the current visit. */
    fun unlock(pin: String): Boolean {
        ensureLoaded()
        val profileIndex = loadedProfileIndex ?: return false
        if (!stored.matches(profileIndex, pin)) {
            log.i { "Hidden list unlock rejected (profile $profileIndex)" }
            return false
        }
        _uiState.value = _uiState.value.copy(unlocked = true)
        log.i { "Hidden list unlocked with its PIN (profile $profileIndex)" }
        return true
    }

    fun lock() {
        if (_uiState.value.unlocked) {
            _uiState.value = _uiState.value.copy(unlocked = false)
            log.i { "Hidden list locked" }
        }
    }

    fun isHidden(type: String, id: String): Boolean {
        ensureLoaded()
        return _uiState.value.isHidden(type, id)
    }

    /** Moves a title into the Hidden list. Requires the Hidden list to be turned on. */
    fun hide(preview: MetaPreview, nowEpochMs: Long = 0L): Boolean {
        ensureLoaded()
        val profileIndex = loadedProfileIndex ?: return false
        if (stored.digest == null) return false
        val item = HiddenLibraryItem(
            id = preview.id,
            type = preview.type,
            name = preview.name,
            poster = preview.rawPosterUrl ?: preview.poster,
            banner = preview.banner,
            logo = preview.logo,
            description = preview.description,
            releaseInfo = preview.releaseInfo,
            genres = preview.genres,
            hiddenAtEpochMs = nowEpochMs,
        )
        write(profileIndex, stored.copy(items = stored.items.filterNot { it.key == item.key } + item))
        return true
    }

    fun unhide(type: String, id: String) {
        ensureLoaded()
        val profileIndex = loadedProfileIndex ?: return
        val key = hiddenKey(type, id)
        if (stored.items.none { it.key == key }) return
        write(profileIndex, stored.copy(items = stored.items.filterNot { it.key == key }))
    }

    private fun load(profileIndex: Int) {
        loadedProfileIndex = profileIndex
        stored = read(profileIndex)
        _uiState.value = LibraryHiddenUiState(
            pinEnabled = stored.digest != null,
            unlocked = false,
            items = stored.items,
        )
    }

    private fun read(profileIndex: Int): StoredHiddenLibrary =
        store.load(profileIndex)
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { json.decodeFromString(StoredHiddenLibrary.serializer(), it) }.getOrNull() }
            ?: StoredHiddenLibrary()

    private fun write(profileIndex: Int, value: StoredHiddenLibrary) {
        store.save(profileIndex, json.encodeToString(StoredHiddenLibrary.serializer(), value))
        if (loadedProfileIndex == profileIndex) {
            stored = value
            _uiState.value = _uiState.value.copy(pinEnabled = value.digest != null, items = value.items)
        }
    }

    private fun StoredHiddenLibrary.matches(profileIndex: Int, pin: String): Boolean {
        val salt = salt ?: return false
        return digest != null && hashHiddenPin(profileIndex, salt, pin) == digest
    }
}

@Serializable
private data class StoredHiddenLibrary(
    val salt: String? = null,
    val digest: String? = null,
    val items: List<HiddenLibraryItem> = emptyList(),
)
