package app.mangareader.mobile.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.mangareader.mobile.MainActivity
import app.mangareader.mobile.utils.FileUtils
import app.mangareader.mobile.ScrapeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

class NotificationWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val prefs = context.getSharedPreferences("manga_prefs", Context.MODE_PRIVATE)

    private fun setStatus(msg: String) {
        prefs.edit().putString("worker_status", msg).apply()
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            setStatus("Starting worker...")

            val cookie = prefs.getString("saved_cookie", "") ?: ""
            if (cookie.isEmpty()) {
                setStatus("Error: Cookie is empty.")
                return@withContext Result.failure()
            }

            val rootUriStr = prefs.getString("last_root_uri", "") ?: ""
            if (rootUriStr.isEmpty()) {
                setStatus("Error: Output Folder URI is empty.")
                return@withContext Result.failure()
            }
            val rootUri = Uri.parse(rootUriStr)

            setStatus("Connecting to Mangago Notification Center...")
            val doc = Jsoup.connect("https://www.mangago.me/home/notification/")
                .header("Cookie", cookie)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .get()

            setStatus("Parsing notification HTML...")
            val lastProcessedTimestamp = prefs.getLong("last_notification_timestamp", 0L)
            val isFirstRun = lastProcessedTimestamp == 0L

            val updateNodes = doc.select("div.notification-wrapper.message")

            var highestTimestampOnPage = 0L
            for (node in updateNodes) {
                val messageText = node.select("div.notice-message").text()
                if (messageText.contains("Manga have new update", ignoreCase = true)) {
                    val timestamp = node.attr("_t").toLongOrNull() ?: 0L
                    if (timestamp > highestTimestampOnPage) {
                        highestTimestampOnPage = timestamp
                    }
                }
            }

            var highestTimestampFound = lastProcessedTimestamp
            val updatedUrls = mutableSetOf<String>()

            for (node in updateNodes) {
                val messageText = node.select("div.notice-message").text()
                if (messageText.contains("Manga have new update", ignoreCase = true)) {
                    val timestamp = node.attr("_t").toLongOrNull() ?: continue

                    val shouldProcess = if (isFirstRun) {
                        timestamp == highestTimestampOnPage
                    } else {
                        timestamp > lastProcessedTimestamp
                    }

                    if (shouldProcess) {
                        if (timestamp > highestTimestampFound) {
                            highestTimestampFound = timestamp
                        }
                        val links = node.select("div.summary-hidden a")
                        for (link in links) {
                            val href = link.attr("href")
                            if (href.isNotBlank()) updatedUrls.add(href)
                        }
                    }
                }
            }

            setStatus("Saving baseline timestamp: ${maxOf(highestTimestampFound, highestTimestampOnPage)}")
            prefs.edit().putLong("last_notification_timestamp", maxOf(highestTimestampFound, highestTimestampOnPage)).apply()

            if (updatedUrls.isEmpty()) {
                setStatus("No new updates found.")
                return@withContext Result.success()
            }

            setStatus("Found ${updatedUrls.size} updated series! Scanning local library...")
            val library = FileUtils.getCachedLibrary(context, rootUri)
            val updatesLog = JSONArray(prefs.getString("notification_log", "[]"))
            var totalNewChaptersFound = 0
            var newSeriesFound = 0

            for (url in updatedUrls) {
                setStatus("Checking series: $url")
                val seriesDoc = Jsoup.connect(url)
                    .header("Cookie", cookie)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .get()

                val h1Title = seriesDoc.select("div.w-title h1").text().trim()
                val mangagoChapters = seriesDoc.select("table#chapter_table tbody tr h4 a.chico")
                    .map { it.text().trim() }

                val localSeries = library.find { it.title.equals(h1Title, ignoreCase = true) }

                val logEntry = JSONObject()
                logEntry.put("title", h1Title)
                logEntry.put("url", url)
                logEntry.put("timestamp", System.currentTimeMillis())
                logEntry.put("status", "pending") // NEW: Mark as pending approval instead of completed

                if (localSeries != null && localSeries.documentFile != null) {
                    val localChapters = FileUtils.getChapters(context, localSeries.documentFile)

                    if (localChapters.isNotEmpty()) {
                        val highestLocalChapter = localChapters.last()

                        var exactLocalName = highestLocalChapter.name
                        if (exactLocalName.contains("-")) {
                            exactLocalName = exactLocalName.substringAfter("-").trim()
                        }
                        exactLocalName = exactLocalName.substringBeforeLast(".").trim()

                        val mangagoIndex = mangagoChapters.indexOfFirst {
                            it.equals(exactLocalName, ignoreCase = true) || it.contains(exactLocalName, ignoreCase = true)
                        }

                        if (mangagoIndex > 0) {
                            val totalOnline = mangagoChapters.size
                            val startChap = totalOnline - mangagoIndex + 1
                            val localNumMatch = Regex("\\d+").find(highestLocalChapter.name)
                            val baseChap = if (localNumMatch != null) localNumMatch.value.toInt() + 1 else startChap

                            logEntry.put("type", "update")
                            logEntry.put("new_count", mangagoIndex)
                            logEntry.put("last_local_name", highestLocalChapter.name)
                            logEntry.put("startChap", startChap)
                            logEntry.put("baseChap", baseChap)

                            updatesLog.put(logEntry)
                            totalNewChaptersFound += mangagoIndex
                            setStatus("Logged $mangagoIndex new chapters for $h1Title (Pending Approval).")
                        } else {
                            setStatus("$h1Title is already up to date locally.")
                        }
                    }
                } else {
                    val newCount = minOf(3, mangagoChapters.size)
                    val totalOnline = mangagoChapters.size
                    val startChap = maxOf(1, totalOnline - 2)

                    logEntry.put("type", "new_series")
                    logEntry.put("new_count", newCount)
                    logEntry.put("startChap", startChap)
                    logEntry.put("baseChap", startChap)

                    updatesLog.put(logEntry)
                    newSeriesFound++
                    setStatus("Logged new series: $h1Title (Pending Approval).")
                }
            }

            prefs.edit()
                .putString("notification_log", updatesLog.toString())
                .apply()

            if (totalNewChaptersFound > 0 || newSeriesFound > 0) {
                sendSystemNotification(totalNewChaptersFound, newSeriesFound)
            }

            setStatus("Finished successfully. Waiting for user approval.")
            Result.success()

        } catch (e: org.jsoup.HttpStatusException) {
            setStatus("Cloudflare Blocked Request (HTTP ${e.statusCode})!")
            e.printStackTrace()
            Result.retry()
        } catch (e: Exception) {
            setStatus("Crashed: ${e.message}")
            e.printStackTrace()
            Result.retry()
        }
    }

    private fun sendSystemNotification(updatedChapters: Int, newSeries: Int) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "MangaUpdatesChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Manga Updates", NotificationManager.IMPORTANCE_DEFAULT)
            manager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var text = ""
        if (updatedChapters > 0) text += "$updatedChapters new chapters found. "
        if (newSeries > 0) text += "$newSeries new series detected. "
        text += "Tap to review and download."

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Mangago Updates Waiting!")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(300, notification)
    }
}