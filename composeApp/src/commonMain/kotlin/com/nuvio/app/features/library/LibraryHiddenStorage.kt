package com.nuvio.app.features.library

/** Per-profile storage for the Hidden list (its own PIN hash and the hidden titles). */
internal expect object LibraryHiddenStorage {
    fun loadPayload(profileIndex: Int): String?
    fun savePayload(profileIndex: Int, payload: String)
    fun removePayload(profileIndex: Int)
}
