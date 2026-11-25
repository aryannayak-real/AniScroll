package chimahon

data class DictionaryPaths(
    val termPaths: List<String> = emptyList(),
    val freqPaths: List<String> = emptyList(),
    val pitchPaths: List<String> = emptyList(),
    val kanjiPaths: List<String> = emptyList(),
)

/** True when at least one local dictionary can answer term lookups. */
val DictionaryPaths.hasLookupSources: Boolean
    get() = termPaths.isNotEmpty() || freqPaths.isNotEmpty() || pitchPaths.isNotEmpty()

/** True when any local dictionary folder is configured, including kanji-only dictionaries. */
val DictionaryPaths.hasAnySources: Boolean
    get() = hasLookupSources || kanjiPaths.isNotEmpty()
