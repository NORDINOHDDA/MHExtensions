package eu.kanade.tachiyomi.extension.en.manhuarmtl

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlin.math.roundToInt
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.ConcurrentHashMap

@Source
abstract class ManhuaRMTL :
    Madara(),
    ConfigurableSource {

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val mangaSubString = "manga"

    // Custom client with OCR text-overlay interceptor
    override val client: OkHttpClient = network.client.newBuilder()
        .addNetworkInterceptor(::ocrImageInterceptor)
        .build()

    // Thread-safe storage for OCR text boxes, keyed by full image URL
    private val ocrData = ConcurrentHashMap<String, List<OcrTextBox>>()

    // Site excludes adult content by default; override to show everything unless the user opts out
    override val adultContentFilterOptions: Map<String, String> = mapOf(
        "Show all (incl. adult)" to "",
        "Hide adult" to "0",
        "Adult only" to "1",
    )

    // Custom sort values — manhuarmtl.com uses ?sort= (not ?m_orderby=)
    override val orderByFilterOptions: Map<String, String> = mapOf(
        "Relevance" to "relevance",
        "Latest" to "latest",
        "Oldest update" to "latest_asc",
        "Trending" to "trending",
        "Newest" to "new",
        "Oldest" to "new_asc",
        "Title A-Z" to "az",
        "Title Z-A" to "za",
        "Most chapters" to "chapters",
        "Fewest chapters" to "chapters_asc",
        "Top rated" to "rating",
        "Most bookmarked" to "bookmarks",
    )

    private val preferences = getPreferences()

    // ============================== Popular / Latest ==============================

    // Site uses custom MRM card layout instead of standard Madara
    override fun popularMangaSelector() = "li.mrm-r-item"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        url = element.selectFirst("a.mrm-r-item__link")?.attr("href")?.substringAfter(baseUrl) ?: ""
        title = element.selectFirst("a.mrm-r-item__link")?.attr("title") ?: ""
        thumbnail_url = element.selectFirst("span.mrm-r-item__art img")?.attr("abs:src")?.trim()
    }

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    // Helper: appends &adult=0 to browse URLs when "Hide NSFW" setting is ON
    private fun browseUrl(sort: String, page: Int): String = "$baseUrl/$mangaSubString/${searchPage(page)}?sort=$sort".let { if (preferences.hideNsfw()) "$it&adult=0" else it }

    // Site uses ?sort= instead of ?m_orderby=
    override fun popularMangaRequest(page: Int): Request = GET(browseUrl("trending", page), headers)

    override fun latestUpdatesRequest(page: Int): Request = GET(browseUrl("latest", page), headers)

    override fun popularMangaNextPageSelector(): String? = "a.next.page-numbers, a.mrm-pager__btn[rel=next]"

    // ============================== Search ==============================

    // Search uses the root endpoint with &pg=N pagination (NOT path-based /page/N/)
    override fun searchRequest(page: Int, query: String, filters: FilterList): Request {
        val url = "$baseUrl/".toHttpUrl().newBuilder().apply {
            addQueryParameter("post_type", "wp-manga")
            addQueryParameter("s", query)
            if (page > 1) addQueryParameter("pg", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is AuthorFilter -> if (filter.state.isNotBlank()) addQueryParameter("author", filter.state)
                    is ArtistFilter -> if (filter.state.isNotBlank()) addQueryParameter("artist", filter.state)
                    is YearFilter -> if (filter.state.isNotBlank()) addQueryParameter("release", filter.state)
                    is StatusFilter -> filter.state.forEach { if (it.state) addQueryParameter("status[]", it.id) }
                    is OrderByFilter -> if (filter.toUriPart().isNotBlank()) addQueryParameter("sort", filter.toUriPart())
                    is AdultContentFilter -> addQueryParameter("adult", if (preferences.hideNsfw()) "0" else filter.toUriPart())
                    is GenreConditionFilter -> addQueryParameter("op", filter.toUriPart())
                    is GenreList -> filter.state.filter { it.state }.forEach { addQueryParameter("genre[]", it.id) }
                    is ExcludeGenreList -> filter.state.filter { it.state }.forEach { addQueryParameter("exclude_genre[]", it.id) }
                    else -> {}
                }
            }
        }.build()

        return GET(url, headers)
    }

    override fun searchMangaNextPageSelector(): String? = "a.next.page-numbers, a.mrm-pager__btn[rel=next]"

    // ============================== Genres ==============================

    // Override the genre request — the form lives on the search results page
    override fun genresRequest(): Request = GET("$baseUrl/?post_type=wp-manga&s=", headers)

    // Custom MRM chips layout — NOT the standard Madara checkbox-group
    override fun parseGenres(document: Document): List<Genre> = document.select("div.mrm-fgroup__chips label.mrm-gchip--in")
        .map { label ->
            val name = label.selectFirst("span")?.text() ?: label.text()
            val id = label.selectFirst("input[type=checkbox]")?.`val`() ?: name
            Genre(name, id)
        }

    // ============================== Manga Details ==============================

    // Custom MRM "hero" layout selectors
    override val mangaDetailsSelectorTitle = "h1.mrm-hero__title"
    override val mangaDetailsSelectorThumbnail = "div.mrm-hero__cover img"
    override val mangaDetailsSelectorAuthor = ".post-content_item:contains(Author) .author-content a, .post-content_item:contains(Author) .summary-content a"
    override val mangaDetailsSelectorArtist = ".post-content_item:contains(Artist) .artist-content a, .post-content_item:contains(Artist) .summary-content a"
    override val mangaDetailsSelectorStatus = ".post-content_item:contains(Status) .summary-content"
    override val mangaDetailsSelectorDescription = "div.description-summary div.summary__content, div.summary_content div.post-content_item > h5:contains(Summary) + div, div.mrm-panel div.summary__content"
    override val mangaDetailsSelectorGenre = "div.mrm-genres__list a[rel=tag]"
    override val mangaDetailsSelectorTag = ""
    override val seriesTypeSelector = ".post-content_item:contains(Type) .summary-content"

    // Alt names live in the MRM hero block, not the standard post-content row
    override val altNameSelector = "p.mrm-hero__alt"

    override fun mangaDetailsParse(document: Document): SManga {
        val manga = SManga.create()
        with(document) {
            manga.title = selectFirst(mangaDetailsSelectorTitle)?.ownText() ?: ""
            select(mangaDetailsSelectorAuthor).map { it.text() }.filter { it.notUpdating() }.joinToString().takeIf { it.isNotBlank() }?.let { manga.author = it }
            select(mangaDetailsSelectorArtist).map { it.text() }.filter { it.notUpdating() }.joinToString().takeIf { it.isNotBlank() }?.let { manga.artist = it }

            // Raw synopsis
            val synopsis = selectFirst(mangaDetailsSelectorDescription)?.let {
                if (it.select("p").text().isNotEmpty()) {
                    it.select("p").joinToString(separator = "\n\n") { p -> p.text().replace("<br>", "\n") }
                } else {
                    it.text()
                }
            }

            selectFirst(mangaDetailsSelectorThumbnail)?.let { manga.thumbnail_url = imageFromElement(it) }

            selectFirst(mangaDetailsSelectorStatus)?.let {
                val statusText = it.text().filter { ch -> ch.isLetterOrDigit() || ch.isWhitespace() }.trim()
                manga.status = when {
                    completedStatusList.any { c -> c.equals(statusText, true) } -> SManga.COMPLETED
                    ongoingStatusList.any { c -> c.equals(statusText, true) } -> SManga.ONGOING
                    hiatusStatusList.any { c -> c.equals(statusText, true) } -> SManga.ON_HIATUS
                    canceledStatusList.any { c -> c.equals(statusText, true) } -> SManga.CANCELLED
                    else -> SManga.UNKNOWN
                }
            }

            // Extract type early — used for both genre chips and info line
            val type = selectFirst(seriesTypeSelector)?.ownText()?.takeIf { it.isNotBlank() && it.notUpdating() }

            // Genres (optionally include type: Manhwa/Manhua/Manga)
            val genreList = select(mangaDetailsSelectorGenre).mapTo(ArrayList()) { it.text() }
            if (preferences.showTypeInGenre() && type != null) {
                genreList.add(type)
            }
            manga.genre = genreList.distinctBy(String::lowercase).joinToString().ifBlank { null }

            // ===== Build comix-style description =====
            val showAltNames = preferences.showAltNames()
            val showExtraInfo = preferences.showExtraInfo()
            val scorePosition = preferences.getScorePosition()

            // Alt names
            val altNames = selectFirst(altNameSelector)?.ownText()?.takeIf { it.isNotBlank() && it.notUpdating() }

            // Rating / votes from MRM facts — site uses a 0-5 scale (NOT 0-10 like comix)
            val ratingText = selectFirst("li.mrm-facts__item--rating strong")?.text()
            val ratingScore = ratingText?.toFloatOrNull()
            val votesText = selectFirst("li.mrm-facts__item--rating .mrm-facts__sub")?.text()
            val votesCount = Regex("""(\d+)""").find(votesText ?: "")?.value?.toIntOrNull() ?: 0
            val hasScore = ratingScore != null && votesCount > 0

            val stars = if (hasScore) {
                val score = ratingScore!!
                // Site uses 0-5 scale: round to nearest int (5.0 → 5 stars, 4.4 → 4, 4.8 → 5)
                val fullStars = score.roundToInt().coerceIn(0, 5)
                "★".repeat(fullStars) + "☆".repeat(5 - fullStars) + " $score"
            } else {
                null
            }

            // Type / chapters / views / release year
            val chaptersText = selectFirst(".post-content_item:contains(Chapters) .summary-content")?.text()
            val chaptersNum = chaptersText?.filter { it.isDigit() }?.toIntOrNull()
            val releaseYear = selectFirst(".post-content_item:contains(Release) .summary-content a")?.text()
                ?: selectFirst(".post-content_item:contains(Release) .summary-content")?.ownText()
            val viewsText = selectFirst("li.mrm-facts__item:has(i.ion-md-eye)")?.text()
            val views = viewsText?.filter { it.isDigit() }

            val infoLine = if (showExtraInfo) {
                buildString {
                    if (type != null) append("**Type:** $type")
                    if (releaseYear != null) {
                        if (isNotEmpty()) append(" · ")
                        append("**Year:** $releaseYear")
                    }
                    if (chaptersNum != null && chaptersNum > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("**Chapters:** $chaptersNum")
                    }
                    if (views != null && views.isNotBlank()) {
                        if (isNotEmpty()) append(" · ")
                        append("**Views:** $views")
                    }
                    if (manga.status != SManga.UNKNOWN) {
                        if (isNotEmpty()) append(" · ")
                        append("**Status:** ${formatStatus(manga.status)}")
                    }
                    if (hasScore) {
                        if (isNotEmpty()) append(" · ")
                        append("**$votesCount ratings**")
                    }
                }.ifBlank { null }
            } else {
                null
            }

            val desc = buildString {
                if (scorePosition == "top" && stars != null) {
                    append(stars)
                    append("\n")
                    if (infoLine != null) {
                        append(infoLine)
                        append("\n\n")
                    }
                }

                synopsis?.let { append(it) }

                if (showAltNames && altNames != null) {
                    if (isNotEmpty()) append("\n\n")
                    append("Alternative names:\n")
                    append("• $altNames")
                }

                if (scorePosition == "end" && stars != null) {
                    if (isNotEmpty()) append("\n\n")
                    append(stars)
                    if (infoLine != null) {
                        append("\n")
                        append(infoLine)
                    }
                }

                if (scorePosition == "none" && infoLine != null) {
                    if (isNotEmpty()) append("\n\n")
                    append(infoLine)
                }
            }.trim()

            manga.description = desc.ifBlank { synopsis }
            manga.initialized = true
        }

        return manga
    }

    private fun formatStatus(status: Int): String = when (status) {
        SManga.ONGOING -> "Ongoing"
        SManga.COMPLETED -> "Completed"
        SManga.CANCELLED -> "Cancelled"
        SManga.ON_HIATUS -> "On hiatus"
        else -> "Unknown"
    }

    // ============================== Chapters ==============================
    // Standard Madara selectors work — li.wp-manga-chapter is present in the detail HTML.
    // All chapters are in the initial page load (no AJAX needed).

    // ============================== Pages + OCR ==============================
    // The site serves RAW images. English MTL text is a JS overlay fetched from
    // fetch-ocr.php. We parse the OCR credentials from the reading page, fetch the
    // text data, and burn it onto the images via a network interceptor.

    override fun pageListParse(response: Response): List<Page> {
        val html = response.body?.string() ?: ""
        val document = Jsoup.parse(html, response.request.url.toString())

        val pages = pageListParse(document)

        if (preferences.prefersEnglish() && html.isNotBlank()) {
            // Clear previous chapter's OCR data
            ocrData.clear()

            try {
                val credentials = parseOcrCredentials(html)
                if (credentials != null) {
                    val ocrPages = fetchOcrData(credentials)
                    if (ocrPages != null) {
                        // Build filename → text boxes map
                        val ocrByFilename = mutableMapOf<String, List<OcrTextBox>>()
                        for (ocrPage in ocrPages) {
                            val filename = ocrPage.image ?: continue
                            val textBoxes = ocrPage.normalisedTexts()
                            if (textBoxes.isNotEmpty()) {
                                ocrByFilename[filename] = textBoxes
                            }
                        }

                        // Match OCR data to pages by filename
                        for (page in pages) {
                            val imageUrl = page.imageUrl ?: continue
                            val filename = imageUrl.substringAfterLast("/").substringBefore("?")
                            val textBoxes = ocrByFilename[filename]
                            if (textBoxes != null && textBoxes.isNotEmpty()) {
                                ocrData[imageUrl] = textBoxes
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Fall back to raw images
            }
        }

        return pages
    }

    /**
     * Parse OCR credentials from the reading page HTML.
     * The credentials are embedded in an obfuscated JS array like:
     * ["base64cid", "hex64token", timestamp, "hex16nonce", "fetch-ocr-url", "hex32ref"]
     */
    private fun parseOcrCredentials(html: String): OcrCredentials? {
        // Find the fetch-ocr.php URL
        val urlMatch = Regex("""https?://[^"'\s]*fetch-ocr\.php""").find(html) ?: return null
        val gateUrl = urlMatch.value

        // Find the chapter data-id (cid) from the hidden input
        val cidMatch = Regex("""data-id=["'](\d+)["']""").find(html) ?: return null
        val cid = cidMatch.groupValues[1]

        // Find the credentials array: ["...", "hex64", number, "hex16", "url", "hex32"]
        val arrayRegex = Regex(
            """\[\s*"[^"]*"\s*,\s*"([0-9a-f]{64})"\s*,\s*(\d+)\s*,\s*"([0-9a-f]{16})"\s*,\s*"[^"]*fetch-ocr[^"]*"\s*,\s*"([0-9a-f]{32})"\s*\]""",
        )
        val match = arrayRegex.find(html) ?: return null

        return OcrCredentials(
            cid = cid,
            token = match.groupValues[1],
            timestamp = match.groupValues[2].toLongOrNull() ?: 0L,
            nonce = match.groupValues[3],
            gateUrl = gateUrl,
            ref = match.groupValues[4],
        )
    }

    /**
     * Fetch OCR text data from fetch-ocr.php.
     * Returns a list of OcrPage (one per image), or null on failure.
     */
    private fun fetchOcrData(credentials: OcrCredentials): List<OcrPage>? {
        val jsonBody = """{"cid":"${credentials.cid}","ref":"${credentials.ref}"}"""
        val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(credentials.gateUrl)
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("X-Gate-Token", credentials.token)
            .addHeader("X-Gate-Nonce", credentials.nonce)
            .addHeader("X-Gate-Timestamp", credentials.timestamp.toString())
            .addHeader("Referer", baseUrl)
            .addHeader("Origin", baseUrl)
            .build()

        return try {
            val response = client.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (body.isNullOrBlank()) return null

            // Try parsing as bare array first, then as envelope
            try {
                body.parseAs<List<OcrPage>>()
            } catch (_: Exception) {
                try {
                    body.parseAs<OcrResponse>().pages()
                } catch (_: Exception) {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Network interceptor that overlays English MTL text on raw chapter images.
     * Only runs when "English (MTL)" mode is selected in settings.
     */
    private fun ocrImageInterceptor(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (!preferences.prefersEnglish()) return response
        if (!response.isSuccessful) return response

        val url = request.url.toString()

        // Only process images from the CDN
        if (!url.contains("cdn.manhuarmmtl.com") && !url.contains("manhuarmmtl.com")) return response

        // Look up OCR text boxes for this image URL
        val textBoxes = ocrData[url] ?: return response
        if (textBoxes.isEmpty()) return response

        // Read the image bytes
        val imageBytes = response.body?.bytes() ?: return response
        if (imageBytes.isEmpty()) return response

        // Overlay text on the image
        val modifiedBytes = overlayText(imageBytes, textBoxes) ?: return response

        // Build new response with modified image
        val contentType = response.body?.contentType()
        val newBody = modifiedBytes.toResponseBody(contentType)

        return response.newBuilder()
            .body(newBody)
            .build()
    }

    /**
     * Burn English text boxes onto a raw image bitmap.
     * Returns the modified image as WebP bytes, or null on failure.
     */
    private fun overlayText(imageBytes: ByteArray, textBoxes: List<OcrTextBox>): ByteArray? {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return null
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        for (textBox in textBoxes) {
            val x = textBox.box.getOrElse(0) { 0f }
            val y = textBox.box.getOrElse(1) { 0f }
            val w = textBox.box.getOrElse(2) { 0f }
            val h = textBox.box.getOrElse(3) { 0f }

            if (w <= 0 || h <= 0) continue

            val text = textBox.text
            if (text.isBlank()) continue

            val fontSize = h * 0.65f

            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = fontSize
                setShadowLayer(fontSize * 0.2f, 2f, 2f, Color.BLACK)
            }

            // Use StaticLayout for word-wrapping within the box width
            val boxWidth = w.toInt().coerceAtLeast(1)
            @Suppress("DEPRECATION")
            val layout = StaticLayout(text, paint, boxWidth, Layout.Alignment.ALIGN_NORMAL, 1f, 0f, false)

            canvas.save()
            canvas.translate(x, y)
            layout.draw(canvas)
            canvas.restore()
        }

        val output = java.io.ByteArrayOutputStream()
        mutableBitmap.compress(Bitmap.CompressFormat.WEBP, 95, output)

        if (bitmap != mutableBitmap) bitmap.recycle()
        mutableBitmap.recycle()

        return output.toByteArray()
    }

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!.trim(), headers.newBuilder().set("Referer", baseUrl).build())

    // ============================== Filters ==============================

    private class ExcludeGenreList(title: String, genres: List<Genre>) : Filter.Group<GenreCheckBox>(title, genres.map { GenreCheckBox(it.name, it.id) })

    override fun getFilterList(): FilterList {
        launchIO { fetchGenres() }

        val filters = mutableListOf<Filter<*>>(
            AuthorFilter("Author"),
            ArtistFilter("Artist"),
            YearFilter("Release year"),
            StatusFilter(
                title = "Status",
                status = statusFilterOptions.map { Tag(it.key, it.value) },
            ),
            OrderByFilter(
                title = "Sort by",
                options = orderByFilterOptions.toList(),
                state = 1, // Default: Latest
            ),
            AdultContentFilter(
                title = "Adult content",
                options = adultContentFilterOptions.toList(),
            ),
        )

        if (genresList.isNotEmpty()) {
            filters += listOf(
                Filter.Separator(),
                Filter.Header("Genres (include)"),
                GenreConditionFilter(
                    title = "Genre match mode",
                    options = genreConditionFilterOptions.toList(),
                ),
                GenreList(
                    title = "Genres",
                    genres = genresList,
                ),
                Filter.Separator(),
                Filter.Header("Genres (exclude)"),
                ExcludeGenreList(
                    title = "Exclude genres",
                    genres = genresList,
                ),
            )
        } else if (fetchGenres) {
            filters += listOf(
                Filter.Separator(),
                Filter.Header("Press 'Reset' to attempt to load genres"),
            )
        }

        return FilterList(filters)
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: androidx.preference.PreferenceScreen) {
        // Chapter text mode (English MTL overlay vs Raw)
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_CHAPTER_TEXT_MODE
            title = "Chapter text"
            summary = "English (MTL overlay) burns translated text onto raw images. Raw shows original images only."
            entries = arrayOf("English (MTL overlay)", "Raw images only")
            entryValues = arrayOf("en", "raw")
            setDefaultValue("en")
        }.let(screen::addPreference)

        // Hide NSFW content globally
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_HIDE_NSFW
            title = "Hide NSFW content"
            summary = "Hide adult content from browse and search (overrides the Adult content filter)"
            setDefaultValue(false)
        }.let(screen::addPreference)

        // Show alt names
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_ALT_NAMES
            title = "Show alternative names"
            summary = "Display alternative titles in the description"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Show extra info
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_EXTRA_INFO
            title = "Show extra info in description"
            summary = "Display type, status, year, chapters, views, rating"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Show type in genre chips
        androidx.preference.SwitchPreferenceCompat(screen.context).apply {
            key = PREF_SHOW_TYPE_IN_GENRE
            title = "Show type in genre chips"
            summary = "Include Manhwa/Manhua/Manga in the genre field"
            setDefaultValue(true)
        }.let(screen::addPreference)

        // Score display position
        androidx.preference.ListPreference(screen.context).apply {
            key = PREF_SCORE_POSITION
            title = "Score display position"
            summary = "Where to display the manga score"
            entries = arrayOf("Don't show", "Top of description", "End of description")
            entryValues = arrayOf("none", "top", "end")
            setDefaultValue("end")
        }.let(screen::addPreference)
    }

    private fun android.content.SharedPreferences.prefersEnglish(): Boolean = getString(PREF_CHAPTER_TEXT_MODE, "en") == "en"
    private fun android.content.SharedPreferences.hideNsfw(): Boolean = getBoolean(PREF_HIDE_NSFW, false)
    private fun android.content.SharedPreferences.showAltNames(): Boolean = getBoolean(PREF_SHOW_ALT_NAMES, true)
    private fun android.content.SharedPreferences.showExtraInfo(): Boolean = getBoolean(PREF_SHOW_EXTRA_INFO, true)
    private fun android.content.SharedPreferences.showTypeInGenre(): Boolean = getBoolean(PREF_SHOW_TYPE_IN_GENRE, true)
    private fun android.content.SharedPreferences.getScorePosition(): String = getString(PREF_SCORE_POSITION, "end") ?: "end"

    companion object {
        private const val PREF_CHAPTER_TEXT_MODE = "pref_chapter_text_mode"
        private const val PREF_HIDE_NSFW = "pref_hide_nsfw"
        private const val PREF_SHOW_ALT_NAMES = "pref_show_alt_names"
        private const val PREF_SHOW_EXTRA_INFO = "pref_show_extra_info"
        private const val PREF_SHOW_TYPE_IN_GENRE = "pref_show_type_in_genre"
        private const val PREF_SCORE_POSITION = "pref_score_position"
    }
}
