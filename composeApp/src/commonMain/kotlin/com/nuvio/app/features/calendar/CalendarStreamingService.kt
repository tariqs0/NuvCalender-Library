package com.nuvio.app.features.calendar

/**
 * Streaming services offered in the Global calendar filter. A release belongs to a service when
 * TMDB lists it as that service's original (network) or as streaming there in the viewer's region
 * (watch provider). Anything on none of them falls under "Others".
 */
enum class CalendarStreamingService(
    val displayName: String,
    internal val tmdbNetworkIds: List<Int>,
    internal val tmdbProviderIds: List<Int>,
) {
    Netflix("Netflix", listOf(213), listOf(8, 1796)),
    AppleTv("Apple TV+", listOf(2552), listOf(350)),
    PrimeVideo("Prime Video", listOf(1024), listOf(9, 119)),
    Max("HBO / Max", listOf(49, 3186), listOf(1899, 384)),
    DisneyPlus("Disney+", listOf(2739), listOf(337)),
    Hulu("Hulu", listOf(453), listOf(15)),
    ParamountPlus("Paramount+", listOf(4330), listOf(531, 2303, 2616, 582)),
    Peacock("Peacock", listOf(3353), listOf(386, 387)),
    Crunchyroll("Crunchyroll", listOf(1112), listOf(283)),
    ;

    companion object {
        /** Refinement value for releases that are on none of the services above. */
        const val OTHERS = "Others"
    }
}
