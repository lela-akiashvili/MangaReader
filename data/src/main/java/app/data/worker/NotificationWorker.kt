package app.data.worker

import android.R.drawable
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import androidx.core.app.NotificationCompat.Builder
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.mangareader.mobile.MainActivity
import app.data.datasource.local.FileUtils
import app.mangareader.mobile.ScrapeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.HttpStatusException
import org.jsoup.Jsoup
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.collections.find
import kotlin.collections.isNotEmpty
import kotlin.jvm.java

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
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)

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
            val updatedUrls = mutableSetOf<String>()
            val urlToNoticeTime = mutableMapOf<String, Long>()

            for (node in updateNodes) {
                val messageText = node.select("div.notice-message").text()
                if (messageText.contains("Manga have new update", ignoreCase = true)) {
                    val timestamp = node.attr("_t").toLongOrNull() ?: continue

                    if (timestamp > highestTimestampOnPage) {
                        highestTimestampOnPage = timestamp
                    }

                    val shouldProcess = if (isFirstRun) {
                        timestamp == highestTimestampOnPage
                    } else {
                        timestamp > lastProcessedTimestamp
                    }

                    if (shouldProcess) {
                        val links = node.select("div.summary-hidden a")
                        for (link in links) {
                            val href = link.attr("href")
                            if (href.isNotBlank()) {
                                updatedUrls.add(href)

                                val existing = urlToNoticeTime[href]
                                if (existing == null || timestamp < existing) {
                                    urlToNoticeTime[href] = timestamp
                                }
                            }
                        }
                    }
                }
            }

            // NEW PORTABLE LOGIC: Read from 'pending_updates.json' on the USB Drive!
            var oldLogJson = "[]"
            try {
                val pendingFile = rootDoc?.findFile("pending_updates.json")
                if (pendingFile != null) {
                    oldLogJson = context.contentResolver.openInputStream(pendingFile.uri)?.bufferedReader()?.use { it.readText() } ?: "[]"
                }
            } catch (e: Exception) { e.printStackTrace() }

            val oldLogArray = JSONArray(oldLogJson)
            val existingLogsMap = mutableMapOf<String, JSONObject>()

            for (i in 0 until oldLogArray.length()) {
                val obj = oldLogArray.getJSONObject(i)
                val url = obj.getString("url")
                existingLogsMap[url] = obj

                if (obj.optString("status") == "pending") {
                    updatedUrls.add(url)
                }
            }

            setStatus("Saving baseline timestamp: ${maxOf(lastProcessedTimestamp, highestTimestampOnPage)}")
            prefs.edit().putLong("last_notification_timestamp", maxOf(lastProcessedTimestamp, highestTimestampOnPage)).apply()

            if (updatedUrls.isEmpty()) {
                setStatus("No new updates found.")
                return@withContext Result.success()
            }

            setStatus("Found ${updatedUrls.size} series to check! Scanning local library...")
            val library = FileUtils.getCachedLibrary(context, rootUri)
            val trackerData = FileUtils.getTracker(context, rootUri)
            var totalNewChaptersFound = 0
            var newSeriesFound = 0

            for (url in updatedUrls) {
                setStatus("Checking series: $url")
                val seriesDoc = Jsoup.connect(url)
                    .header("Cookie", cookie)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .get()

                val h1Title = seriesDoc.select("div.w-title h1").text().trim()
                val rows = seriesDoc.select("table#chapter_table tbody tr")
                val mangagoChapters = mutableListOf<String>()

                val noticeTimestampSec = existingLogsMap[url]?.optLong("notice_time")?.takeIf { it > 0L }
                    ?: urlToNoticeTime[url]
                    ?: lastProcessedTimestamp

                val noticeTimeMs = noticeTimestampSec * 1000L
                val cutoffMs = noticeTimeMs - (86400000L * 1.5).toLong()
                val dateFormat = SimpleDateFormat("MMM d, yyyy", Locale.US)

                var newByDateCount = 0

                for (row in rows) {
                    val titleNode = row.select("h4 a.chico")
                    if (titleNode.isEmpty()) continue

                    val chapterName = titleNode.text().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                    mangagoChapters.add(chapterName)

                    val isExplicitNew = row.select("b").text().contains("new", ignoreCase = true)
                    val dateText = row.select("td.no").last()?.text()?.trim() ?: ""

                    var isNewByDate = false
                    try {
                        val chapDate = dateFormat.parse(dateText)
                        if (chapDate != null && chapDate.time >= cutoffMs) {
                            isNewByDate = true
                        }
                    } catch (e: Exception) {
                        if (dateText.contains("Today", ignoreCase = true) ||
                            dateText.contains("Yesterday", ignoreCase = true) ||
                            dateText.contains("ago", ignoreCase = true)) {
                            isNewByDate = true
                        }
                    }

                    if (isExplicitNew || isNewByDate) {
                        newByDateCount++
                    }
                }

                if (newByDateCount == 0 && urlToNoticeTime.containsKey(url)) {
                    newByDateCount = 1
                }

                val trackerEntry = trackerData.find { it.title.equals(h1Title, ignoreCase = true) || it.url == url }
                val localSeries = library.find { it.title.equals(h1Title, ignoreCase = true) }
                val exactLocalTitle = trackerEntry?.title ?: localSeries?.title ?: h1Title

                val isNewEntry = !existingLogsMap.containsKey(url)
                val logEntry = existingLogsMap[url] ?: JSONObject().apply {
                    put("url", url)
                    put("timestamp", System.currentTimeMillis())
                    put("type", "update")
                    put("new_count", 0)
                    put("last_local_name", "")
                }

                logEntry.put("title", exactLocalTitle)
                logEntry.put("notice_time", noticeTimestampSec)

                var highestName = ""
                var highestNumber = 0
                var foundLocalData = false

                if (trackerEntry != null) {
                    highestName = trackerEntry.lastChapterName
                    highestNumber = trackerEntry.lastChapterNumber
                    foundLocalData = true
                } else if (localSeries != null && localSeries.documentFile != null) {
                    val localChapters = FileUtils.getChapters(context, localSeries.documentFile)
                    if (localChapters.isNotEmpty()) {
                        val highestLocalChapter = localChapters.last()
                        highestName = highestLocalChapter.name
                        if (highestName.contains("-")) {
                            highestName = highestName.substringAfter("-").trim()
                        }
                        highestName = highestName.substringBeforeLast(".").trim()

                        val localNumMatch = Regex("\\d+").find(highestLocalChapter.name)
                        highestNumber = localNumMatch?.value?.toIntOrNull() ?: 1
                        foundLocalData = true

                        FileUtils.updateTrackerEntry(context, rootUri, exactLocalTitle, url, highestName, highestNumber)
                    }
                }

                if (foundLocalData) {
                    val indexByStringMatch = mangagoChapters.indexOfFirst { onlineName ->
                        val cleanOnline = onlineName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
                        val cleanHighest = highestName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()

                        cleanOnline == cleanHighest ||
                                (cleanHighest.isNotEmpty() && cleanOnline.contains(cleanHighest)) ||
                                (cleanOnline.isNotEmpty() && cleanHighest.contains(cleanOnline))
                    }

                    val missingByLocalProgress = if (indexByStringMatch != -1) {
                        indexByStringMatch
                    } else if (highestNumber > 0) {
                        maxOf(0, mangagoChapters.size - highestNumber)
                    } else {
                        0
                    }

                    val mangagoIndex = minOf(missingByLocalProgress, newByDateCount)

                    if (mangagoIndex > 0) {
                        val totalOnline = mangagoChapters.size
                        val startChap = totalOnline - mangagoIndex + 1
                        val baseChap = highestNumber + 1

                        logEntry.put("type", "update")
                        logEntry.put("new_count", mangagoIndex)
                        logEntry.put("last_local_name", highestName)
                        logEntry.put("startChap", startChap)
                        logEntry.put("baseChap", baseChap)
                        logEntry.put("status", "pending")

                        existingLogsMap[url] = logEntry

                        if (isNewEntry) totalNewChaptersFound += mangagoIndex
                        setStatus("Logged $mangagoIndex new chapters for $exactLocalTitle.")
                    } else {
                        if (!isNewEntry) {
                            logEntry.put("status", "completed")
                            logEntry.put("new_count", 0)
                            existingLogsMap[url] = logEntry
                        }
                        setStatus("$exactLocalTitle is up to date locally.")
                    }
                } else {
                    val newCount = minOf(3, mangagoChapters.size)
                    val totalOnline = mangagoChapters.size
                    val startChap = maxOf(1, totalOnline - 2)

                    logEntry.put("type", "new_series")
                    logEntry.put("new_count", newCount)
                    logEntry.put("startChap", startChap)
                    logEntry.put("baseChap", startChap)
                    logEntry.put("status", "pending")

                    existingLogsMap[url] = logEntry

                    if (isNewEntry) newSeriesFound++
                    setStatus("Logged new series: $exactLocalTitle (Pending).")
                }

                if (ScrapeState.isCancelled.value) {
                    setStatus("Worker aborted by user.")
                    break
                }
            }

            // NEW PORTABLE LOGIC: Save the map back to the USB 'pending_updates.json'
            val newLogArray = JSONArray()
            existingLogsMap.values.forEach { newLogArray.put(it) }

            try {
                var outFile = rootDoc?.findFile("pending_updates.json")
                if (outFile == null) outFile = rootDoc?.createFile("application/json", "pending_updates.json")
                outFile?.uri?.let { uri ->
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(newLogArray.toString(4).toByteArray())
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            // Cleanup the old phone memory just in case
            prefs.edit().remove("notification_log").apply()

            if (totalNewChaptersFound > 0 || newSeriesFound > 0) {
                sendSystemNotification(totalNewChaptersFound, newSeriesFound)
            }

            setStatus("Finished successfully. Waiting for user approval.")
            Result.success()

        } catch (e: HttpStatusException) {
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

        if (VERSION.SDK_INT >= VERSION_CODES.O) {
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

        val notification = Builder(context, channelId)
            .setContentTitle("Mangago Updates Waiting!")
            .setContentText(text)
            .setSmallIcon(drawable.ic_popup_sync)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        manager.notify(300, notification)
    }
}