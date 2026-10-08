package com.nuvio.app.features.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.core.ui.ScreenActivityEffect
import com.nuvio.app.features.profiles.PinEntryDialog
import com.nuvio.app.features.profiles.PinVerifyResult
import com.nuvio.app.features.profiles.ProfilePinState
import com.nuvio.app.features.profiles.ProfileRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.library_hidden_confirm_pin
import nuvio.composeapp.generated.resources.library_hidden_current_pin
import nuvio.composeapp.generated.resources.library_hidden_new_pin
import nuvio.composeapp.generated.resources.library_hidden_pin_format
import nuvio.composeapp.generated.resources.library_hidden_pin_mismatch
import nuvio.composeapp.generated.resources.library_hidden_pin_same_as_profile
import nuvio.composeapp.generated.resources.library_hidden_pin_wrong
import nuvio.composeapp.generated.resources.library_hidden_profile_pin_first
import nuvio.composeapp.generated.resources.library_hidden_profile_pin_required
import nuvio.composeapp.generated.resources.library_hidden_title
import nuvio.composeapp.generated.resources.library_hidden_turn_off_title
import org.jetbrains.compose.resources.stringResource

@Composable
private fun HiddenPinError.message(): String = stringResource(
    when (this) {
        HiddenPinError.InvalidFormat -> Res.string.library_hidden_pin_format
        HiddenPinError.Mismatch -> Res.string.library_hidden_pin_mismatch
        HiddenPinError.SameAsProfilePin -> Res.string.library_hidden_pin_same_as_profile
        HiddenPinError.WrongPin -> Res.string.library_hidden_pin_wrong
        HiddenPinError.ProfilePinUnverified -> Res.string.library_hidden_profile_pin_required
    },
)

/**
 * Turns the Hidden list on or changes its PIN: current PIN (when changing), new PIN, then the
 * new PIN again. The new PIN must differ from the profile's lock PIN.
 */
@Composable
internal fun HiddenPinSetupDialog(
    profileIndex: Int = ProfileRepository.activeProfileId,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasExistingPin = remember(profileIndex) { LibraryHiddenRepository.isPinEnabled(profileIndex) }
    // Without a local hash of the profile PIN, confirm it once so the two can be compared.
    val needsProfilePin = remember(profileIndex) {
        LibraryHiddenRepository.profilePinState(profileIndex) == ProfilePinState.NotCached
    }
    var step by remember {
        mutableStateOf(
            when {
                needsProfilePin -> SetupStep.ProfilePin
                hasExistingPin -> SetupStep.Current
                else -> SetupStep.New
            },
        )
    }
    var currentPin by remember { mutableStateOf<String?>(null) }
    var newPin by remember { mutableStateOf("") }
    val wrongPin = HiddenPinError.WrongPin.message()
    val sameAsProfile = HiddenPinError.SameAsProfilePin.message()
    val mismatch = HiddenPinError.Mismatch.message()
    val format = HiddenPinError.InvalidFormat.message()
    val profilePinRequired = HiddenPinError.ProfilePinUnverified.message()

    when (step) {
        SetupStep.ProfilePin -> PinEntryDialog(
            profileName = stringResource(Res.string.library_hidden_profile_pin_first),
            onVerify = { pin -> ProfileRepository.verifyPin(profileIndex, pin) },
            onVerified = { step = if (hasExistingPin) SetupStep.Current else SetupStep.New },
            onDismiss = onDismiss,
        )

        SetupStep.Current -> PinEntryDialog(
            profileName = stringResource(Res.string.library_hidden_current_pin),
            onVerify = { pin ->
                PinVerifyResult(unlocked = LibraryHiddenRepository.verifyPin(pin, profileIndex), message = wrongPin)
            },
            onVerified = { pin ->
                currentPin = pin
                step = SetupStep.New
            },
            onDismiss = onDismiss,
        )

        SetupStep.New -> PinEntryDialog(
            profileName = stringResource(Res.string.library_hidden_new_pin),
            onVerify = { pin ->
                when (validateNewHiddenPin(pin, pin, LibraryHiddenRepository.matchesProfilePin(profileIndex, pin))) {
                    null -> PinVerifyResult(unlocked = true)
                    HiddenPinError.SameAsProfilePin -> PinVerifyResult(message = sameAsProfile)
                    else -> PinVerifyResult(message = format)
                }
            },
            onVerified = { pin ->
                newPin = pin
                step = SetupStep.Confirm
            },
            onDismiss = onDismiss,
        )

        SetupStep.Confirm -> PinEntryDialog(
            profileName = stringResource(Res.string.library_hidden_confirm_pin),
            onVerify = { pin ->
                when (LibraryHiddenRepository.setPin(newPin, pin, currentPin, profileIndex)) {
                    null -> PinVerifyResult(unlocked = true)
                    HiddenPinError.Mismatch -> PinVerifyResult(message = mismatch)
                    HiddenPinError.SameAsProfilePin -> PinVerifyResult(message = sameAsProfile)
                    HiddenPinError.WrongPin -> PinVerifyResult(message = wrongPin)
                    HiddenPinError.InvalidFormat -> PinVerifyResult(message = format)
                    HiddenPinError.ProfilePinUnverified -> PinVerifyResult(message = profilePinRequired)
                }
            },
            onVerified = { onDone() },
            onDismiss = onDismiss,
        )
    }
}

private enum class SetupStep { ProfilePin, Current, New, Confirm }

/** Asks for the Hidden list PIN; every visit needs it again. */
@Composable
internal fun HiddenUnlockDialog(onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    val wrongPin = HiddenPinError.WrongPin.message()
    PinEntryDialog(
        profileName = stringResource(Res.string.library_hidden_title),
        onVerify = { pin -> PinVerifyResult(unlocked = LibraryHiddenRepository.unlock(pin), message = wrongPin) },
        onVerified = { onUnlocked() },
        onDismiss = onDismiss,
    )
}

/** Turns the Hidden list off after the PIN; its titles return to the regular lists. */
@Composable
internal fun HiddenTurnOffDialog(
    profileIndex: Int = ProfileRepository.activeProfileId,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val wrongPin = HiddenPinError.WrongPin.message()
    PinEntryDialog(
        profileName = stringResource(Res.string.library_hidden_turn_off_title),
        onVerify = { pin ->
            PinVerifyResult(unlocked = LibraryHiddenRepository.disable(pin, profileIndex) == null, message = wrongPin)
        },
        onVerified = { onDone() },
        onDismiss = onDismiss,
    )
}

/**
 * Locks the Hidden list as soon as it is left: when it stops being shown (another Library tab)
 * or when the Library screen goes inactive (another app tab, a details page, the player...).
 */
@Composable
internal fun HiddenListAutoLock(open: Boolean, onLocked: () -> Unit) {
    ScreenActivityEffect(open) { active ->
        if (open && !active) {
            LibraryHiddenRepository.lock()
            onLocked()
        }
    }
    DisposableEffect(open) {
        onDispose { if (open) LibraryHiddenRepository.lock() }
    }
}
