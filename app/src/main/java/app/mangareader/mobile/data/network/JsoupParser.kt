package app.mangareader.mobile.data.network

import android.content.Context
import app.mangareader.mobile.data.downloader.HeavyDutyWebScraper
import app.mangareader.mobile.data.model.Chapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

object JsoupParser {

    suspend fun fetchChapters(
        context: Context,
        mangaUrl: String,
        cookieString: String,
        userAgent: String,
        updateLog: (String) -> Unit
    ): List<Chapter> {
        return withContext(Dispatchers.IO) {
            val chapterList = mutableListOf<Chapter>()
            var document: org.jsoup.nodes.Document? = null

            try {
                // 1. Always use the Headless Browser and poll the DOM for the table
                updateLog("Booting up Headless Browser (Desktop Mode)...")

                val html = HeavyDutyWebScraper.getHtmlForChapterList(context, mangaUrl)

                if (html.isNotEmpty()) {
                    document = Jsoup.parse(html, mangaUrl)
                    updateLog("Page downloaded via headless browser.")
                } else {
                    updateLog("Error: Received empty HTML. Could still be blocked.")
                }
            } catch (e: Exception) {
                updateLog("Fatal Error fetching page: ${e.message}")
            }

            if (document != null) {
                updateLog("Searching for table#chapter_table...")

                // 2. Safely extract rows using the table structure
                val elements = document.select("table#chapter_table tr")

                updateLog("Found ${elements.size} potential rows. Parsing...")

                for (element in elements) {
                    val linkElement = element.select("a").first()
                    if (linkElement != null) {
                        val url = linkElement.attr("abs:href")
                        val title = linkElement.text().trim()
                        // Clean extraction: grab the last td.no to ensure we just get the date, not the group name
                        val date = element.select("td.no, span").last()?.text()?.trim() ?: ""

                        if (url.isNotEmpty() && title.isNotEmpty()) {
                            chapterList.add(Chapter(title, url, date))
                        }
                    }
                }

                // 3. Manga lists are almost always descending (Newest first).
                // Reversing the list here makes Chapter 1 = Index 0, ensuring max counts pull correctly.
                if (chapterList.isNotEmpty()) {
                    chapterList.reverse()
                    updateLog("Successfully extracted ${chapterList.size} chapters.")
                } else {
                    updateLog("Extracted 0 chapters. Layout might have changed.")
                }
            }

            chapterList
        }
    }
}