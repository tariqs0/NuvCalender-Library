package com.nuvio.app.features.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.Chip
import com.nuvio.app.features.calendar.CalendarDensity
import com.nuvio.app.features.calendar.CalendarMonthDisplay
import com.nuvio.app.features.calendar.CalendarSettingsRepository
import com.nuvio.app.features.calendar.CalendarViewMode
import com.nuvio.app.features.calendar.CalendarWeekStartOptions
import com.nuvio.app.features.calendar.weekdayName
import com.nuvio.app.features.search.SearchHistoryRepository
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_settings_page_addons
import nuvio.composeapp.generated.resources.compose_settings_page_plugins
import nuvio.composeapp.generated.resources.settings_content_discovery_addons_description
import nuvio.composeapp.generated.resources.settings_content_discovery_addons_description_appstore
import nuvio.composeapp.generated.resources.settings_content_discovery_plugins_description
import nuvio.composeapp.generated.resources.settings_content_discovery_section_sources
import nuvio.composeapp.generated.resources.settings_content_discovery_section_search
import nuvio.composeapp.generated.resources.settings_content_discovery_recent_searches
import nuvio.composeapp.generated.resources.settings_content_discovery_recent_searches_description
import nuvio.composeapp.generated.resources.calendar_view_day
import nuvio.composeapp.generated.resources.calendar_view_month
import nuvio.composeapp.generated.resources.calendar_view_week
import nuvio.composeapp.generated.resources.settings_calendar_density
import nuvio.composeapp.generated.resources.settings_calendar_density_comfortable
import nuvio.composeapp.generated.resources.settings_calendar_density_compact
import nuvio.composeapp.generated.resources.settings_calendar_density_description
import nuvio.composeapp.generated.resources.settings_calendar_density_spacious
import nuvio.composeapp.generated.resources.settings_calendar_default_view
import nuvio.composeapp.generated.resources.settings_calendar_default_view_description
import nuvio.composeapp.generated.resources.settings_calendar_first_day
import nuvio.composeapp.generated.resources.settings_calendar_first_day_description
import nuvio.composeapp.generated.resources.settings_calendar_global
import nuvio.composeapp.generated.resources.settings_calendar_home_row
import nuvio.composeapp.generated.resources.settings_calendar_home_row_description
import nuvio.composeapp.generated.resources.settings_calendar_show_in_navigation
import nuvio.composeapp.generated.resources.settings_calendar_show_in_navigation_description
import nuvio.composeapp.generated.resources.settings_calendar_month_display
import nuvio.composeapp.generated.resources.settings_calendar_month_display_description
import nuvio.composeapp.generated.resources.settings_calendar_month_display_posters
import nuvio.composeapp.generated.resources.settings_calendar_month_display_titles
import nuvio.composeapp.generated.resources.settings_calendar_global_description
import nuvio.composeapp.generated.resources.settings_calendar_section
import org.jetbrains.compose.resources.stringResource

internal fun LazyListScope.contentDiscoveryContent(
    isTablet: Boolean,
    showPluginsEntry: Boolean,
    onAddonsClick: () -> Unit,
    onPluginsClick: () -> Unit,
) {
    item {
        val recentSearchesEnabled by remember {
            SearchHistoryRepository.ensureLoaded()
            SearchHistoryRepository.enabled
        }.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.settings_content_discovery_section_search),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_content_discovery_recent_searches),
                    description = stringResource(Res.string.settings_content_discovery_recent_searches_description),
                    checked = recentSearchesEnabled,
                    isTablet = isTablet,
                    onCheckedChange = SearchHistoryRepository::setEnabled,
                )
            }
        }
    }

    item {
        val calendarSettings by remember {
            CalendarSettingsRepository.ensureLoaded()
            CalendarSettingsRepository.uiState
        }.collectAsStateWithLifecycle()

        SettingsSection(
            title = stringResource(Res.string.settings_calendar_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_calendar_global),
                    description = stringResource(Res.string.settings_calendar_global_description),
                    checked = calendarSettings.globalCalendarEnabled,
                    isTablet = isTablet,
                    onCheckedChange = CalendarSettingsRepository::setGlobalCalendarEnabled,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_calendar_home_row),
                    description = stringResource(Res.string.settings_calendar_home_row_description),
                    checked = calendarSettings.showLibraryRowOnHome,
                    isTablet = isTablet,
                    onCheckedChange = CalendarSettingsRepository::setShowLibraryRowOnHome,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsSwitchRow(
                    title = stringResource(Res.string.settings_calendar_show_in_navigation),
                    description = stringResource(Res.string.settings_calendar_show_in_navigation_description),
                    checked = calendarSettings.showInNavigation,
                    isTablet = isTablet,
                    onCheckedChange = CalendarSettingsRepository::setShowInNavigation,
                )
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChipRow(
                    title = stringResource(Res.string.settings_calendar_default_view),
                    description = stringResource(Res.string.settings_calendar_default_view_description),
                    isTablet = isTablet,
                ) {
                    listOf(
                        CalendarViewMode.Month to Res.string.calendar_view_month,
                        CalendarViewMode.Week to Res.string.calendar_view_week,
                        CalendarViewMode.Day to Res.string.calendar_view_day,
                    ).forEach { (mode, label) ->
                        Chip(
                            label = stringResource(label),
                            selected = calendarSettings.viewMode == mode,
                            onClick = { CalendarSettingsRepository.setViewMode(mode) },
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChipRow(
                    title = stringResource(Res.string.settings_calendar_month_display),
                    description = stringResource(Res.string.settings_calendar_month_display_description),
                    isTablet = isTablet,
                ) {
                    listOf(
                        CalendarMonthDisplay.Titles to Res.string.settings_calendar_month_display_titles,
                        CalendarMonthDisplay.Posters to Res.string.settings_calendar_month_display_posters,
                    ).forEach { (display, label) ->
                        Chip(
                            label = stringResource(label),
                            selected = calendarSettings.monthDisplay == display,
                            onClick = { CalendarSettingsRepository.setMonthDisplay(display) },
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChipRow(
                    title = stringResource(Res.string.settings_calendar_first_day),
                    description = stringResource(Res.string.settings_calendar_first_day_description),
                    isTablet = isTablet,
                ) {
                    CalendarWeekStartOptions.forEach { day ->
                        Chip(
                            label = weekdayName(day),
                            selected = calendarSettings.firstDayOfWeek == day,
                            onClick = { CalendarSettingsRepository.setFirstDayOfWeek(day) },
                        )
                    }
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsChipRow(
                    title = stringResource(Res.string.settings_calendar_density),
                    description = stringResource(Res.string.settings_calendar_density_description),
                    isTablet = isTablet,
                ) {
                    listOf(
                        CalendarDensity.Compact to Res.string.settings_calendar_density_compact,
                        CalendarDensity.Comfortable to Res.string.settings_calendar_density_comfortable,
                        CalendarDensity.Spacious to Res.string.settings_calendar_density_spacious,
                    ).forEach { (density, label) ->
                        Chip(
                            label = stringResource(label),
                            selected = calendarSettings.density == density,
                            onClick = { CalendarSettingsRepository.setDensity(density) },
                        )
                    }
                }
            }
        }
    }

    item {
        SettingsSection(
            title = stringResource(Res.string.settings_content_discovery_section_sources),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_addons),
                    description = stringResource(
                        if (AppFeaturePolicy.personalMediaAddonCopyEnabled) {
                            Res.string.settings_content_discovery_addons_description_appstore
                        } else {
                            Res.string.settings_content_discovery_addons_description
                        },
                    ),
                    isTablet = isTablet,
                    onClick = onAddonsClick,
                )
                if (showPluginsEntry) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_plugins),
                        description = stringResource(Res.string.settings_content_discovery_plugins_description),
                        isTablet = isTablet,
                        onClick = onPluginsClick,
                    )
                }
            }
        }
    }
}
