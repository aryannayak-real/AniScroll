package chimahon.dictionary

import android.text.Html
import android.util.Log
import chimahon.GlossaryEntry
import chimahon.LookupResult
import chimahon.TermResult
import chimahon.TransformGroup
import chimahon.dictionary.ko.KoreanAnalyzerDeinflector
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Online fallback for users who have not imported an offline dictionary.
 *
 * The source is chosen from the active profile language. Japanese uses Jisho;
 * other languages use the English Wiktionary definition API. Script detection
 * handles Korean and Japanese text when no profile language is set. Imported
 * Yomitan dictionaries always take priority over this fallback.
 */
object OnlineDictionary {

    private const val TAG = "OnlineDictionary"
    private const val JISHO_BASE_URL = "https://jisho.org/api/v1/search/words"
    private const val WIKTIONARY_BASE_URL = "https://en.wiktionary.org/api/rest_v1/page/definition"
    private const val WIKTIONARY_API_URL = "https://en.wiktionary.org/w/api.php"
    private const val USER_AGENT = "AniScroll (+https://github.com/aryannayak-real/AniScroll)"
    private const val JISHO_DICT_NAME = "Jisho (online)"
    private const val WIKTIONARY_DICT_NAME = "Wiktionary (online)"
    private const val CACHE_TTL_MS = 10 * 60 * 1000L
    private const val CACHE_LIMIT = 64
    private const val MAX_SENSES_PER_ENTRY = 8
    private const val MAX_CANDIDATES = 6
    private const val MAX_CHINESE_SECTIONS = 8
    private const val MAX_ZH_SEE_DEPTH = 2

    /** Wiktionary section headings that contain Chinese glosses. */
    private val CHINESE_DEFINITION_SECTIONS = setOf(
        "Definitions",
        "Noun",
        "Verb",
        "Adjective",
        "Adverb",
        "Interjection",
        "Pronoun",
        "Proper noun",
        "Conjunction",
        "Preposition",
        "Postposition",
        "Particle",
        "Classifier",
        "Numeral",
        "Determiner",
        "Phrase",
        "Proverb",
        "Idiom",
        "Prefix",
        "Suffix",
        "Circumfix",
        "Infix",
        "Root",
        "Letter",
        "Symbol",
        "Punctuation mark",
        "Romanization",
        "Counter",
    )

    data class Result(
        val results: List<LookupResult>,
        val error: String?,
    )

    private data class CacheEntry(
        val cachedAt: Long,
        val result: Result,
    )

    private data class WikiSection(
        val index: Int,
        val level: Int,
        val line: String,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry>?): Boolean =
            size > CACHE_LIMIT
    }

    @Synchronized
    fun lookup(query: String, languageCode: String = "", maxResults: Int = 20): Result {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return Result(emptyList(), null)

        val targets = lookupTargets(trimmed, languageCode)
        val cacheKey = "${targets.joinToString(",")}\u0000$maxResults\u0000$trimmed"
        cache[cacheKey]?.let { entry ->
            if (System.currentTimeMillis() - entry.cachedAt < CACHE_TTL_MS) {
                return entry.result
            }
            cache.remove(cacheKey)
        }

        val perTarget = maxOf(1, maxResults / targets.size)
        val results = LinkedHashMap<String, LookupResult>()
        var hadFailure = false

        for (target in targets) {
            val targetResults: List<LookupResult> = try {
                when (target) {
                    "ja" -> {
                        val jisho = try {
                            lookupJisho(trimmed, perTarget)
                        } catch (e: Exception) {
                            hadFailure = true
                            Log.w(TAG, "Jisho lookup failed for '$trimmed'", e)
                            emptyList()
                        }
                        jisho.ifEmpty { lookupWiktionary(trimmed, target, perTarget) }
                    }
                    "zh" -> {
                        val wiktionary = try {
                            lookupWiktionary(trimmed, target, perTarget)
                        } catch (e: Exception) {
                            hadFailure = true
                            Log.w(TAG, "Wiktionary REST lookup failed for '$trimmed'", e)
                            emptyList()
                        }
                        wiktionary.ifEmpty { lookupChineseWiktionary(trimmed, perTarget) }
                    }
                    else -> lookupWiktionary(trimmed, target, perTarget)
                }
            } catch (e: Exception) {
                hadFailure = true
                Log.w(TAG, "Online lookup failed for '$trimmed' ($target)", e)
                emptyList()
            }

            for (result in targetResults) {
                val key = result.term.expression + "\u0000" +
                    result.term.glossaries.joinToString("|") { it.dictName + ":" + it.glossary }
                if (key !in results) results[key] = result
                if (results.size >= maxResults) break
            }
            if (results.size >= maxResults) break
        }

        val finalResults = results.values.toList()
        val result = Result(
            results = finalResults,
            error = if (finalResults.isEmpty() && hadFailure) {
                "Online dictionary unavailable. Check your internet connection."
            } else {
                null
            },
        )
        if (!hadFailure) {
            cache[cacheKey] = CacheEntry(System.currentTimeMillis(), result)
        }
        return result
    }

    /**
     * The active profile language wins when it is set. Without one, Hangul and
     * kana identify Korean and Japanese; Han-only text is queried in both
     * Japanese and Chinese so manhua and manga both work without configuration.
     */
    private fun lookupTargets(query: String, languageCode: String): List<String> {
        if (query.any(::isHangul)) return listOf("ko")
        if (query.any(::isKana)) return listOf("ja")

        val profileLanguage = languageCode.trim().lowercase().take(2)
        if (profileLanguage.isNotBlank()) return listOf(profileLanguage)

        if (query.any(::isHan)) return listOf("ja", "zh")
        return listOf("en")
    }

    private fun lookupJisho(query: String, maxResults: Int): List<LookupResult> {
        val url = JISHO_BASE_URL.toHttpUrl()
            .newBuilder()
            .addQueryParameter("keyword", query)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Jisho returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: error("Jisho returned an empty response")
            return parseJisho(body, query, maxResults)
        }
    }

    private fun lookupWiktionary(query: String, language: String, maxResults: Int): List<LookupResult> {
        val results = ArrayList<LookupResult>()
        for (candidate in lookupCandidates(query, language)) {
            val result = fetchWiktionary(query, candidate, language) ?: continue
            results += result
            if (results.size >= maxResults) break
        }
        return results
    }

    /**
     * The REST definition endpoint often has no Chinese section for single Han
     * characters and simplified forms. The rendered page has the definitions
     * (or a "see traditional form" link), so parse the Chinese section there.
     */
    private fun lookupChineseWiktionary(
        query: String,
        maxResults: Int,
        depth: Int = 0,
    ): List<LookupResult> {
        val sections = fetchWikiSections(query) ?: return emptyList()
        val chineseSection = sections.firstOrNull { it.level == 2 && it.line == "Chinese" }
            ?: return emptyList()
        val chineseHtml = fetchWikiSectionHtml(query, chineseSection.index) ?: return emptyList()

        followChineseRedirect(chineseHtml, query, maxResults, depth)?.let { return it }

        val childSections = sections
            .dropWhile { it.index != chineseSection.index }
            .drop(1)
            .takeWhile { it.level > chineseSection.level }
            .filter { it.line in CHINESE_DEFINITION_SECTIONS }
            .take(MAX_CHINESE_SECTIONS)

        val sectionHtml = if (childSections.isEmpty()) {
            listOf(chineseSection.line to chineseHtml)
        } else {
            childSections.mapNotNull { section ->
                fetchWikiSectionHtml(query, section.index)?.let { section.line to it }
            }
        }

        val glossaries = ArrayList<GlossaryEntry>()
        for ((heading, html) in sectionHtml) {
            followChineseRedirect(html, query, maxResults, depth)?.let { return it }

            val definitions = parseChineseDefinitions(html)
            if (definitions.isEmpty()) continue
            glossaries += GlossaryEntry(
                dictName = WIKTIONARY_DICT_NAME,
                glossary = definitions.joinToString("; "),
                definitionTags = if (heading == "Definitions") "" else heading,
                termTags = "",
            )
            if (glossaries.size >= maxResults) break
        }
        if (glossaries.isEmpty()) return emptyList()

        val rules = glossaries.map { it.definitionTags }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        return listOf(
            LookupResult(
                matched = query,
                deinflected = query,
                process = emptyArray(),
                term = TermResult(
                    expression = query,
                    reading = "",
                    rules = rules,
                    glossaries = glossaries.toTypedArray(),
                    frequencies = emptyArray(),
                    pitches = emptyArray(),
                ),
                preprocessorSteps = 0,
            ),
        )
    }

    private fun followChineseRedirect(
        html: String,
        query: String,
        maxResults: Int,
        depth: Int,
    ): List<LookupResult>? {
        if (depth >= MAX_ZH_SEE_DEPTH) return null
        val target = parseZhSeeTarget(html) ?: return null
        if (target == query) return null

        return lookupChineseWiktionary(target, maxResults, depth + 1)
            .takeIf { it.isNotEmpty() }
            ?.map { result ->
                result.copy(
                    matched = query,
                    deinflected = result.term.expression,
                    process = arrayOf(TransformGroup("Traditional form", result.term.expression)),
                )
            }
    }

    private fun parseChineseDefinitions(html: String): List<String> {
        val document = Jsoup.parse(html)
        val list = document.selectFirst("ol") ?: return emptyList()
        return buildList {
            for (item in list.children()) {
                if (item.tagName() != "li") continue
                val cleaned = item.clone()
                cleaned.select("dl, ul, ol, style, sup.reference, .mw-editsection").remove()
                val text = cleaned.text()
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (text.isNotBlank()) add(text)
                if (size >= MAX_SENSES_PER_ENTRY) break
            }
        }
    }

    private fun parseZhSeeTarget(html: String): String? {
        val document = Jsoup.parse(html)
        val table = document.selectFirst("table.zh-see") ?: return null
        val link = table.select("span.Hant a")
            .firstOrNull { it.attr("href").startsWith("/wiki/") }
            ?: table.select("a")
                .firstOrNull { it.attr("href").startsWith("/wiki/") }
            ?: return null
        val encoded = link.attr("href")
            .removePrefix("/wiki/")
            .substringBefore("#")
        if (encoded.isBlank()) return null
        return runCatching {
            URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
        }.getOrNull()
    }

    private fun fetchWikiSections(page: String): List<WikiSection>? {
        val body = fetchWikiApi(
            mapOf(
                "action" to "parse",
                "page" to page,
                "prop" to "sections",
            ),
        ) ?: return null
        val sections = JSONObject(body).optJSONObject("parse")
            ?.optJSONArray("sections")
            ?: return null
        return buildList {
            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val index = section.optInt("index", -1)
                val line = section.optString("line").trim()
                if (index < 0 || line.isEmpty()) continue
                add(WikiSection(index, section.optInt("level", 0), line))
            }
        }
    }

    private fun fetchWikiSectionHtml(page: String, sectionIndex: Int): String? {
        val body = fetchWikiApi(
            mapOf(
                "action" to "parse",
                "page" to page,
                "prop" to "text",
                "section" to sectionIndex.toString(),
            ),
        ) ?: return null
        return JSONObject(body).optJSONObject("parse")?.optString("text")
            ?.takeIf { it.isNotBlank() }
    }

    private fun fetchWikiApi(parameters: Map<String, String>): String? {
        val builder = WIKTIONARY_API_URL.toHttpUrl().newBuilder()
        for ((key, value) in parameters) builder.addQueryParameter(key, value)
        builder.addQueryParameter("format", "json")
        builder.addQueryParameter("formatversion", "2")
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()
        }
    }

    private fun lookupCandidates(query: String, language: String): List<String> {
        val deinflector = (
            if (language == "ko") {
                KoreanAnalyzerDeinflector
            } else {
                DeinflectorRegistry.get(language)
            }
        ) ?: return listOf(query)

        return buildList {
            add(query)
            for (preprocessed in deinflector.preProcess(query)) {
                add(preprocessed)
                for (deinflected in deinflector.deinflect(preprocessed, language)) {
                    add(deinflected.text)
                }
            }
        }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_CANDIDATES)
    }

    private fun fetchWiktionary(query: String, candidate: String, language: String): LookupResult? {
        val url = WIKTIONARY_BASE_URL.toHttpUrl()
            .newBuilder()
            .addPathSegment(candidate)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                error("Wiktionary returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return null
            return parseWiktionary(body, query, candidate, language)
        }
    }

    internal fun parseJisho(body: String, query: String, maxResults: Int = 20): List<LookupResult> {
        val data = JSONObject(body).optJSONArray("data") ?: return emptyList()
        val results = ArrayList<LookupResult>(minOf(data.length(), maxResults))

        for (i in 0 until data.length()) {
            if (results.size >= maxResults) break
            val entry = data.optJSONObject(i) ?: continue
            val japanese = firstJapanese(entry) ?: continue
            val senses = entry.optJSONArray("senses") ?: JSONArray()

            val glossaries = buildList {
                for (j in 0 until minOf(senses.length(), MAX_SENSES_PER_ENTRY)) {
                    val sense = senses.optJSONObject(j) ?: continue
                    val definitions = sense.optJSONArray("english_definitions").stringList()
                    if (definitions.isEmpty()) continue
                    val partsOfSpeech = sense.optJSONArray("parts_of_speech").stringList()
                    add(
                        GlossaryEntry(
                            dictName = JISHO_DICT_NAME,
                            glossary = definitions.joinToString("; "),
                            definitionTags = partsOfSpeech.joinToString(" "),
                            termTags = "",
                        ),
                    )
                }
            }
            if (glossaries.isEmpty()) continue

            val rules = senses.optJSONObject(0)
                ?.optJSONArray("parts_of_speech")
                .stringList()
                .joinToString(" ")

            results += LookupResult(
                matched = query,
                deinflected = japanese.first,
                process = emptyArray(),
                term = TermResult(
                    expression = japanese.first,
                    reading = japanese.second,
                    rules = rules,
                    glossaries = glossaries.toTypedArray(),
                    frequencies = emptyArray(),
                    pitches = emptyArray(),
                ),
                preprocessorSteps = 0,
            )
        }

        return results
    }

    internal fun parseWiktionary(
        body: String,
        query: String,
        candidate: String,
        language: String,
        maxResults: Int = 20,
    ): LookupResult? {
        val root = JSONObject(body)
        val sections = root.optJSONArray(language) ?: return null
        val glossaries = buildList {
            for (i in 0 until sections.length()) {
                if (size >= maxResults * MAX_SENSES_PER_ENTRY) break
                val section = sections.optJSONObject(i) ?: continue
                val partOfSpeech = section.optString("partOfSpeech").trim()
                val definitions = section.optJSONArray("definitions") ?: continue
                for (j in 0 until definitions.length()) {
                    val definition = definitions.optJSONObject(j)?.optString("definition") ?: continue
                    val text = cleanWiktionaryHtml(definition)
                    if (text.isBlank()) continue
                    add(
                        GlossaryEntry(
                            dictName = WIKTIONARY_DICT_NAME,
                            glossary = text,
                            definitionTags = partOfSpeech,
                            termTags = "",
                        ),
                    )
                }
            }
        }
        if (glossaries.isEmpty()) return null

        val rules = glossaries.map { it.definitionTags }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
        val process = if (candidate != query) {
            arrayOf(TransformGroup("Base form", candidate))
        } else {
            emptyArray()
        }

        return LookupResult(
            matched = query,
            deinflected = candidate,
            process = process,
            term = TermResult(
                expression = candidate,
                reading = "",
                rules = rules,
                glossaries = glossaries.toTypedArray(),
                frequencies = emptyArray(),
                pitches = emptyArray(),
            ),
            preprocessorSteps = 0,
        )
    }

    private fun firstJapanese(entry: JSONObject): Pair<String, String>? {
        val japanese = entry.optJSONArray("japanese") ?: return null
        for (i in 0 until japanese.length()) {
            val item = japanese.optJSONObject(i) ?: continue
            val reading = item.optString("reading").trim()
            val word = item.optString("word").trim().ifBlank { reading }
            if (word.isNotEmpty()) return word to reading
        }
        return null
    }

    private fun cleanWiktionaryHtml(html: String): String {
        val withoutStyles = html.replace(
            Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL),
            "",
        )
        return Html.fromHtml(withoutStyles, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun isHangul(char: Char): Boolean =
        char in '\uAC00'..'\uD7A3' || char in '\u1100'..'\u11FF'

    private fun isKana(char: Char): Boolean =
        char in '\u3040'..'\u30FF'

    private fun isHan(char: Char): Boolean =
        char in '\u3400'..'\u4DBF' || char in '\u4E00'..'\u9FFF' || char in '\uF900'..'\uFAFF'

    private fun JSONArray?.stringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (i in 0 until length()) {
                val value = optString(i).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
