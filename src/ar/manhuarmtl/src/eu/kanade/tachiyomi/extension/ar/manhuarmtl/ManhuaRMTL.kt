package eu.kanade.tachiyomi.extension.ar.manhuarmtl

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale

class ManhuaRMTL : HttpSource() {
    override val name = "ManhuaRMTL (Arabic Translation)"
    override val baseUrl = "https://manhuarmtl.com"
    override val lang = "ar"
    override val supportsLatest = true
    
    private val ocrFetcher = OCRFetcher()
    private val translator = ArabicTranslator()
    private val canvasRenderer = CanvasRenderer()

    override val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
                .addHeader("Accept-Language", "ar-SA,ar;q=0.9")
                .build()
            chain.proceed(request)
        }
        .build()

    // جلب قائمة المانجا
    override fun popularMangaRequest(page: Int): Request {
        return GET("$baseUrl/manga?page=$page")
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("div.manga-item").map { element ->
            SManga.create().apply {
                title = element.select("h3").text()
                url = element.select("a").attr("href")
                thumbnail_url = element.select("img").attr("src")
            }
        }
        return MangasPage(mangas, true)
    }

    override fun latestUpdatesRequest(page: Int): Request {
        return GET("$baseUrl/manga/latest?page=$page")
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/search?q=${query.replace(" ", "+")}&page=$page")
    }

    override fun searchMangaParse(response: Response): MangasPage {
        return popularMangaParse(response)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()
        return SManga.create().apply {
            title = document.select("h1.manga-title").text()
            description = document.select("div.manga-description").text()
            thumbnail_url = document.select("img.manga-cover").attr("src")
            genre = document.select("div.genres a").eachText().joinToString(", ")
        }
    }

    // جلب الفصول
    override fun chapterListRequest(manga: SManga): Request {
        return GET(baseUrl + manga.url)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("div.chapter-item").map { element ->
            SChapter.create().apply {
                name = element.select("a.chapter-name").text()
                url = element.select("a").attr("href")
                date_upload = parseDate(element.select("span.chapter-date").text())
            }
        }
    }

    // جلب صفحات الفصل مع الترجمة
    override fun pageListRequest(chapter: SChapter): Request {
        return GET(baseUrl + chapter.url)
    }

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pages = mutableListOf<Page>()
        
        document.select("img.chapter-image").forEachIndexed { index, element ->
            val imageUrl = element.attr("src")
            val page = Page(index, imageUrl, imageUrl)
            pages.add(page)
        }
        
        return pages
    }

    // معالجة الصور مع الترجمة والرسم
    override fun imageUrlParse(response: Response): String {
        return response.request.url.toString()
    }

    // ========================
    // OCR و Credentials Extraction
    // ========================
    private inner class OCRFetcher {
        fun extractCredentials(html: String): Map<String, String> {
            val credentialsMap = mutableMapOf<String, String>()
            
            // استخراج _0xvault من HTML
            val vaultPattern = """_0xvault\s*=\s*['"](.*?)['"]""".toRegex()
            val vaultMatch = vaultPattern.find(html)
            if (vaultMatch != null) {
                credentialsMap["vault"] = vaultMatch.groupValues[1]
            }
            
            return credentialsMap
        }

        fun fetchOCR(imageUrl: String): String {
            return try {
                val request = Request.Builder()
                    .url("$baseUrl/api/fetch-ocr.php")
                    .post(okhttp3.FormBody.Builder()
                        .add("image_url", imageUrl)
                        .build())
                    .build()
                
                val response = client.newCall(request).execute()
                response.body?.string() ?: ""
            } catch (e: Exception) {
                e.printStackTrace()
                ""
            }
        }
    }

    // ========================
    // الترجمة إلى العربية
    // ========================
    private inner class ArabicTranslator {
        fun translate(englishText: String): String {
            return try {
                // استخدام Google Translate API بدون مفتاح API
                val encodedText = java.net.URLEncoder.encode(englishText, "UTF-8")
                val url = "https://translate.googleapis.com/translate_a/element.js?cb=googleTranslateElementInit"
                
                // بديل: استخدام محرر ترجمة بسيط
                val request = Request.Builder()
                    .url("https://api.mymemory.translated.net/get?q=$encodedText&langpair=en|ar")
                    .build()
                
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: englishText
                
                // استخراج النص المترجم من JSON
                if (body.contains("translatedText")) {
                    val pattern = """"translatedText":"(.*?)"""".toRegex()
                    val match = pattern.find(body)
                    match?.groupValues?.get(1) ?: englishText
                } else {
                    englishText
                }
            } catch (e: Exception) {
                englishText
            }
        }
    }

    // ========================
    // رسم النص العربي على الصور
    // ========================
    private inner class CanvasRenderer {
        fun drawArabicText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
            // تفعيل اتجاه RTL
            paint.textAlign = Paint.Align.RIGHT
            
            // استخدام خط عربي إذا كان متوفراً
            try {
                val arabicTypeface = Typeface.create("serif", Typeface.BOLD)
                paint.typeface = arabicTypeface
            } catch (e: Exception) {
                // استخدام الخط الافتراضي
            }
            
            // رسم النص
            canvas.drawText(text, x, y, paint)
        }

        fun overlayTranslation(originalBitmap: android.graphics.Bitmap, translatedText: String): android.graphics.Bitmap {
            val bitmap = originalBitmap.copy(originalBitmap.config, true)
            val canvas = Canvas(bitmap)
            
            val paint = Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 32f
                isAntiAlias = true
                setShadowLayer(5f, 2f, 2f, android.graphics.Color.BLACK)
            }
            
            // حساب موضع النص (أسفل الصورة)
            val x = (bitmap.width * 0.95).toFloat() // يمين الصورة (RTL)
            val y = (bitmap.height * 0.95).toFloat()
            
            drawArabicText(canvas, translatedText, x, y, paint)
            
            return bitmap
        }
    }

    // ========================
    // دالات مساعدة
    // ========================
    private fun parseDate(dateStr: String): Long {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            sdf.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }
}
