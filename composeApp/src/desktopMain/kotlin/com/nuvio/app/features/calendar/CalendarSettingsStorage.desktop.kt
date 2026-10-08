package com.nuvio.app.features.calendar

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey

internal actual object CalendarSettingsStorage {
    private const val payloadKey = "calendar_settings_payload"
    private val store = DesktopStorage.store("nuvio_calendar_settings")

    actual fun loadPayload(): String? =
        store.getString(ProfileScopedKey.of(payloadKey))

    actual fun savePayload(payload: String) {
        store.putString(ProfileScopedKey.of(payloadKey), payload)
    }
}
