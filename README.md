<div align="center">

  <img src="https://nuvio.tv/assets/nuvio-app-logo-wordmark.webp" alt="Nuvio" width="320" />

  <p>
    A free, open-source media app for Windows, macOS, and Linux.
    <br />
    Bring your own sources. Nuvio turns them into a library with artwork, ratings, subtitles, and your place saved on every screen.
  </p>

  [Website](https://nuvio.tv) · [GitHub releases](https://github.com/NuvioMedia/NuvioDesktop/releases/latest) · [Support Nuvio](https://nuvio.tv/support)

</div>

## About this fork

**NuvCalender-Library** is a fork of [NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) that adds a **Calendar** page for release dates — both everything coming out and what's coming for the titles in your Library. Everything else is the upstream app.

## Calendar

A dedicated **Calendar** tab with two calendars:

- **Global Release Calendar** — movie releases, series premieres, season premieres and new episodes from all available sources (Cinemeta and TMDB), merged so each title or episode appears once.
- **Library Calendar** — episodes, season premieres and movie releases (theatrical and digital) for every title in your Library or Watchlist.

### Features

- **Month, Week and Day views.** Week view keeps the dates pinned while you scroll.
- **Titles or Posters** in the month grid — toggle it from the page header; posters fill each day and scroll sideways.
- **Hover preview** (desktop) and a **quick-actions** dialog on click: View details, Add to Library, Mark as watched (per episode for episodes).
- **Type filter:** All · Movies · TV / Series · Anime.
- **Filters:** sort (All · New · A–Z · Rating), genre, language, country and streaming service (Netflix, Apple TV+, Prime Video, HBO / Max, Disney+, Hulu, Paramount+, Peacock, Crunchyroll, Others) — the same filters work in both calendars.

### Settings

Settings → **Content & Discovery** → **Calendar**:

| Setting | What it does |
|---|---|
| Global Release Calendar | Show or hide the Global calendar. When hidden, the page shows only your Library calendar on a single toolbar row. |
| Show Library Calendar row on Home | A Home row with each Library title's next release in the coming 30 days; *View all* opens the Calendar. |
| Show Calendar in navigation | Show or hide the Calendar in the sidebar and navigation bar. Hiding it turns the Home row on, so the Calendar stays one tap away. |
| Default view | Month, Week or Day. |
| Month display | Titles or Posters. |
| First day of the week | Monday, Sunday or Saturday. |
| Density | Compact, Comfortable or Spacious. |

### Data sources

- **Cinemeta** (no key needed) — recent and upcoming episodes for popular, current-year, Home-catalog and Library series, plus dated movies.
- **TMDB** — every scripted show airing in the month, premieres, movie releases and streaming services. Add a TMDB API key in **Settings → Integrations → TMDB Enrichment** to enable these; without one the Global calendar uses Cinemeta only and every title is listed under "Others" for streaming services.
- "Where to watch" uses your device region (US when it isn't set).

### Tests

```bash
./gradlew :composeApp:desktopTest --tests "com.nuvio.app.features.calendar.*"
```

## ⚠️ Alpha Software - Slow Development - Testers Only

Nuvio Desktop is currently in alpha and is intended only for testers. It is under development and is not suitable for daily use.

Expect breaking changes with every update. Features, settings, stored data, and compatibility may change or stop working without notice. Do not rely on this build as your primary media app, and report any issues you encounter during testing.

## Get Nuvio Desktop

Download the latest build from [GitHub Releases](https://github.com/NuvioMedia/NuvioDesktop/releases/latest).

- Windows: MSI installer
- macOS: DMG installer
- Linux: DEB, RPM, Flatpak, or AppImage

## Build from source

```bash
git clone https://github.com/tariqs0/NuvCalender-Library.git
cd NuvCalender-Library
```

This repository uses Git LFS for bundled binaries; install [Git LFS](https://git-lfs.com) before cloning. Upstream (without the Calendar): [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop).

### Run the app

```bash
./gradlew :composeApp:run
```

On Windows PowerShell:

```powershell
.\gradlew.bat :composeApp:run
```

### Package the app

Build a release package for the current host:

```bash
./gradlew :composeApp:packageReleaseDistributionForCurrentOS
```

For platform-specific packaging:

Windows PowerShell:

```powershell
.\gradlew.bat :composeApp:packageReleaseMsi --rerun-tasks
```

macOS:

```bash
./scripts/build-macos-release-dmgs.sh --package-only
```

Linux:

```bash
./gradlew :composeApp:packageReleaseDeb
```

The shared app is built with Kotlin Multiplatform and Compose Multiplatform.

## License

[GNU General Public License v3.0](./LICENSE)
