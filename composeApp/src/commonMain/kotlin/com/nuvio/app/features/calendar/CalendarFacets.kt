package com.nuvio.app.features.calendar

/**
 * Genre / language / country of a calendar entry, normalised across sources: Cinemeta reports
 * genre names and country names, TMDB reports genre ids, ISO country codes and ISO language
 * codes. Everything is stored as canonical English genre names, ISO 3166-1 alpha-2 countries and
 * ISO 639-1 languages so one filter works for all of them.
 */
internal data class CalendarFacets(
    val genres: Set<String> = emptySet(),
    val language: String? = null,
    val countries: Set<String> = emptySet(),
    val services: Set<CalendarStreamingService> = emptySet(),
) {
    /** Japanese animation, the way anime trackers classify it. */
    val isAnime: Boolean
        get() = "Anime" in genres ||
            ("Animation" in genres && (language == "ja" || (language == null && "JP" in countries)))

    companion object {
        fun of(
            genreNames: Iterable<String> = emptyList(),
            language: String? = null,
            countries: Iterable<String> = emptyList(),
        ): CalendarFacets {
            val countryCodes = countries.mapNotNull(::countryCode).toCollection(linkedSetOf())
            val languageCode = language?.trim()?.lowercase()?.substringBefore('-')?.takeIf { it.length in 2..3 }
                // Cinemeta has no language field; the first production country is a good proxy.
                ?: countryCodes.firstOrNull()?.let(PrimaryLanguageByCountry::get)
            return CalendarFacets(
                genres = canonicalGenres(genreNames),
                language = languageCode,
                countries = countryCodes,
            )
        }
    }
}

internal fun canonicalGenres(names: Iterable<String>): Set<String> =
    names.flatMap { it.split('&', ',', '/') }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { GenreAliases[it.lowercase()] ?: it.replaceFirstChar(Char::uppercase) }
        .toCollection(linkedSetOf())

internal fun tmdbGenreNames(ids: Iterable<Int>): List<String> = ids.mapNotNull(TmdbGenreNames::get)

private val GenreAliases = mapOf(
    "science fiction" to "Sci-Fi",
    "sci-fi" to "Sci-Fi",
    "scifi" to "Sci-Fi",
    "politics" to "Politics",
    "tv movie" to "TV Movie",
    "reality-tv" to "Reality",
    "game-show" to "Game Show",
    "talk-show" to "Talk",
    "film-noir" to "Film Noir",
)

// TMDB movie + TV genre ids (TV's combined genres are split by canonicalGenres).
private val TmdbGenreNames = mapOf(
    28 to "Action", 12 to "Adventure", 16 to "Animation", 35 to "Comedy", 80 to "Crime",
    99 to "Documentary", 18 to "Drama", 10751 to "Family", 14 to "Fantasy", 36 to "History",
    27 to "Horror", 10402 to "Music", 9648 to "Mystery", 10749 to "Romance", 878 to "Sci-Fi",
    10770 to "TV Movie", 53 to "Thriller", 10752 to "War", 37 to "Western",
    10759 to "Action & Adventure", 10762 to "Kids", 10763 to "News", 10764 to "Reality",
    10765 to "Sci-Fi & Fantasy", 10766 to "Soap", 10767 to "Talk", 10768 to "War & Politics",
)

/** ISO code for a country given either as a code ("US") or an English name ("United States"). */
internal fun countryCode(raw: String): String? {
    val value = raw.trim()
    if (value.isEmpty()) return null
    if (value.length == 2 && value.all(Char::isLetter)) return value.uppercase()
    return CountryCodesByName[value.lowercase()]
}

internal fun countryDisplayName(code: String): String = CountryNames[code] ?: code

internal fun languageDisplayName(code: String): String = LanguageNames[code] ?: code.uppercase()

private val CountryNames = mapOf(
    "US" to "United States", "GB" to "United Kingdom", "CA" to "Canada", "AU" to "Australia",
    "NZ" to "New Zealand", "IE" to "Ireland", "JP" to "Japan", "KR" to "South Korea", "CN" to "China",
    "TW" to "Taiwan", "HK" to "Hong Kong", "IN" to "India", "FR" to "France", "DE" to "Germany",
    "ES" to "Spain", "IT" to "Italy", "PT" to "Portugal", "BR" to "Brazil", "MX" to "Mexico",
    "AR" to "Argentina", "CO" to "Colombia", "CL" to "Chile", "PE" to "Peru", "NL" to "Netherlands",
    "BE" to "Belgium", "CH" to "Switzerland", "AT" to "Austria", "SE" to "Sweden", "NO" to "Norway",
    "DK" to "Denmark", "FI" to "Finland", "IS" to "Iceland", "PL" to "Poland", "CZ" to "Czech Republic",
    "HU" to "Hungary", "RO" to "Romania", "BG" to "Bulgaria", "GR" to "Greece", "TR" to "Turkey",
    "RU" to "Russia", "UA" to "Ukraine", "IL" to "Israel", "EG" to "Egypt", "SA" to "Saudi Arabia",
    "AE" to "United Arab Emirates", "JO" to "Jordan", "LB" to "Lebanon", "MA" to "Morocco",
    "NG" to "Nigeria", "ZA" to "South Africa", "TH" to "Thailand", "ID" to "Indonesia",
    "PH" to "Philippines", "MY" to "Malaysia", "SG" to "Singapore", "VN" to "Vietnam",
    "PK" to "Pakistan", "BD" to "Bangladesh", "IR" to "Iran", "KW" to "Kuwait", "QA" to "Qatar",
)

private val CountryCodesByName: Map<String, String> = buildMap {
    CountryNames.forEach { (code, name) -> put(name.lowercase(), code) }
    put("usa", "US")
    put("uk", "GB")
    put("korea", "KR")
    put("republic of korea", "KR")
    put("czechia", "CZ")
    put("türkiye", "TR")
    put("russian federation", "RU")
    put("uae", "AE")
}

private val LanguageNames = mapOf(
    "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "cn" to "Cantonese",
    "hi" to "Hindi", "ta" to "Tamil", "te" to "Telugu", "ml" to "Malayalam", "fr" to "French",
    "de" to "German", "es" to "Spanish", "it" to "Italian", "pt" to "Portuguese", "nl" to "Dutch",
    "sv" to "Swedish", "no" to "Norwegian", "nb" to "Norwegian", "da" to "Danish", "fi" to "Finnish",
    "is" to "Icelandic", "pl" to "Polish", "cs" to "Czech", "hu" to "Hungarian", "ro" to "Romanian",
    "bg" to "Bulgarian", "el" to "Greek", "tr" to "Turkish", "ru" to "Russian", "uk" to "Ukrainian",
    "he" to "Hebrew", "ar" to "Arabic", "fa" to "Persian", "ur" to "Urdu", "bn" to "Bengali",
    "th" to "Thai", "id" to "Indonesian", "ms" to "Malay", "tl" to "Tagalog", "vi" to "Vietnamese",
)

private val PrimaryLanguageByCountry = mapOf(
    "US" to "en", "GB" to "en", "CA" to "en", "AU" to "en", "NZ" to "en", "IE" to "en",
    "JP" to "ja", "KR" to "ko", "CN" to "zh", "TW" to "zh", "HK" to "zh", "IN" to "hi",
    "FR" to "fr", "DE" to "de", "AT" to "de", "ES" to "es", "MX" to "es", "AR" to "es",
    "CO" to "es", "CL" to "es", "PE" to "es", "IT" to "it", "PT" to "pt", "BR" to "pt",
    "NL" to "nl", "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi", "IS" to "is",
    "PL" to "pl", "CZ" to "cs", "HU" to "hu", "RO" to "ro", "BG" to "bg", "GR" to "el",
    "TR" to "tr", "RU" to "ru", "UA" to "uk", "IL" to "he", "EG" to "ar", "SA" to "ar",
    "AE" to "ar", "JO" to "ar", "LB" to "ar", "MA" to "ar", "KW" to "ar", "QA" to "ar",
    "IR" to "fa", "PK" to "ur", "BD" to "bn", "TH" to "th", "ID" to "id", "MY" to "ms",
    "PH" to "tl", "VN" to "vi",
)
