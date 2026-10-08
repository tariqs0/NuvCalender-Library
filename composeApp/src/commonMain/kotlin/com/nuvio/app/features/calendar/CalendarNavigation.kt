package com.nuvio.app.features.calendar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Whether navigation (sidebar, top bar, bottom bar) should offer the Calendar tab. */
@Composable
internal fun rememberCalendarInNavigation(): Boolean {
    val settings by remember {
        CalendarSettingsRepository.ensureLoaded()
        CalendarSettingsRepository.uiState
    }.collectAsStateWithLifecycle()
    return settings.showInNavigation
}
