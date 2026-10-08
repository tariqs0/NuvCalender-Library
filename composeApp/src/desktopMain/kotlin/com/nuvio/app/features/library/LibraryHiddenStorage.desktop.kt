package com.nuvio.app.features.library

import com.nuvio.app.core.storage.DesktopStorage

internal actual object LibraryHiddenStorage {
    private val store = DesktopStorage.store("nuvio_library_hidden")

    private fun key(profileIndex: Int) = "library_hidden_$profileIndex"

    actual fun loadPayload(profileIndex: Int): String? = store.getString(key(profileIndex))

    actual fun savePayload(profileIndex: Int, payload: String) {
        store.putString(key(profileIndex), payload)
    }

    actual fun removePayload(profileIndex: Int) {
        store.remove(key(profileIndex))
    }
}
