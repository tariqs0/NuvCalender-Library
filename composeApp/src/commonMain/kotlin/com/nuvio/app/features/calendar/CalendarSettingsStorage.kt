package com.nuvio.app.features.calendar

internal expect object CalendarSettingsStorage {
    fun loadPayload(): String?
    fun savePayload(payload: String)
}
