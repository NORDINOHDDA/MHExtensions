package eu.kanade.tachiyomi.extension.all.manhuarmtl

import eu.kanade.tachiyomi.multisrc.madara.Madara
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import okhttp3.Request
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class ManhuaRMTL : Madara() {

    override val useLoadMoreRequest = LoadMoreStrategy.Never

    override val mangaSubString = "manga"

    // Site uses custom MRM card layout instead of standard Madara
    override fun popularMangaSelector() = "li.mrm-r-item"
    override fun latestUpdatesSelector() = popularMangaSelector()
    override fun searchMangaSelector() = popularMangaSelector()

    override fun popularMangaFromElement(element: Element): SManga = SManga.create().apply {
        url = element.selectFirst("a.mrm-r-item__link")?.attr("href")?.substringAfter(baseUrl) ?: ""
        title = element.selectFirst("a.mrm-r-item__link")?.attr("title") ?: ""
        thumbnail_url = element.selectFirst("span.mrm-r-item__art img")?.attr("src")?.trim()
    }

    override fun latestUpdatesFromElement(element: Element): SManga = popularMangaFromElement(element)
    override fun searchMangaFromElement(element: Element): SManga = popularMangaFromElement(element)

    // Site uses ?sort= instead of ?m_orderby=
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/$mangaSubString/${searchPage(page)}?sort=trending", headers)

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/$mangaSubString/${searchPage(page)}?sort=latest", headers)

    // Custom genres selector for manga details page
    override val mangaDetailsSelectorGenre = ".mrm-genres__list a"

    // Manga details — cover from og:image
    override fun mangaDetailsParse(document: Document): SManga = SManga.create().apply {
        title = document.selectFirst("h1")?.text() ?: ""
        description = document.selectFirst("div.description-summary div.summary__content")?.text()
        genre = document.select(mangaDetailsSelectorGenre).joinToString(", ") { it.text() }
        thumbnail_url = document.selectFirst("meta[property=og:image]")?.attr("content")
        document.select("div.post-content_item").forEach { item ->
            val label = item.selectFirst("div.summary-heading")?.text() ?: return@forEach
            val value = item.selectFirst("div.summary-content")?.text() ?: return@forEach
            when (label.lowercase()) {
                "author(s)" -> author = value
                "artist(s)" -> artist = value
                "status" -> status = when (value.lowercase()) {
                    "ongoing", "releasing" -> SManga.ONGOING
                    "completed" -> SManga.COMPLETED
                    "cancelled" -> SManga.CANCELLED
                    "hiatus", "on hiatus" -> SManga.ON_HIATUS
                    else -> SManga.UNKNOWN
                }
            }
        }
        initialized = true
    }

    // Chapter list and page images use standard Madara selectors — no override needed

    override fun imageRequest(page: Page): Request = GET(page.imageUrl!!, headers.newBuilder().set("Referer", baseUrl).build())
}
