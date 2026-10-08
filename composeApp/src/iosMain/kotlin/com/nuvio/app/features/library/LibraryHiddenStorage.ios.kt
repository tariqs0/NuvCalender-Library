package com.nuvio.app.features.library

import platform.Foundation.NSUserDefaults

internal actual object LibraryHiddenStorage {
    private fun key(profileIndex: Int) = "library_hidden_$profileIndex"

    actual fun loadPayload(profileIndex: Int): String? =
        NSUserDefaults.standardUserDefaults.stringForKey(key(profileIndex))

    actual fun savePayload(profileIndex: Int, payload: String) {
        NSUserDefaults.standardUserDefaults.setObject(payload, forKey = key(profileIndex))
    }

    actual fun removePayload(profileIndex: Int) {
        NSUserDefaults.standardUserDefaults.removeObjectForKey(key(profileIndex))
    }
}
