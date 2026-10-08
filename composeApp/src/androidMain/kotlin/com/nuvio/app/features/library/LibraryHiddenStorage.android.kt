package com.nuvio.app.features.library

import android.content.Context
import android.content.SharedPreferences

internal actual object LibraryHiddenStorage {
    private const val preferencesName = "nuvio_library_hidden"

    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    }

    private fun key(profileIndex: Int) = "library_hidden_$profileIndex"

    actual fun loadPayload(profileIndex: Int): String? = preferences?.getString(key(profileIndex), null)

    actual fun savePayload(profileIndex: Int, payload: String) {
        preferences?.edit()?.putString(key(profileIndex), payload)?.apply()
    }

    actual fun removePayload(profileIndex: Int) {
        preferences?.edit()?.remove(key(profileIndex))?.apply()
    }
}
