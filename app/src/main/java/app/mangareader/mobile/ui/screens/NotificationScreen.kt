package app.mangareader.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.mangareader.mobile.workers.NotificationWorker
import kotlinx.coroutines.delay
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*

private val DarkBlueStart = Color(0xFF0B101E)
private val PurpleEnd = Color(0xFF311545)

data class NotificationLogItem(
    val title: String,
    val url: String,
    val timestamp: Long,
    val type: String,
    val newCount: Int,
    val lastLocalName: String,
    val status: String,
    val startChap: Int,
    val baseChap: Int
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationScreen(onBackClick: () -> Unit) {
    BackHandler { onBackClick() }

    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("manga_prefs", Context.MODE_PRIVATE) }
    var logs by remember { mutableStateOf<List<NotificationLogItem>>(emptyList()) }

    val hasValidCookie by remember { mutableStateOf(prefs.getString("saved_cookie", "")?.isNotEmpty() == true) }
    var workerStatus by remember { mutableStateOf("Idle") }

    // Helper function to update the status of a specific log entry in memory
    fun updateLogStatus(timestamp: Long, newStatus: String) {
        val jsonString = prefs.getString("notification_log", "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getLong("timestamp") == timestamp) {
                    obj.put("status", newStatus)
                    break
                }
            }
            prefs.edit().putString("notification_log", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Helper function to launch ScraperService directly from the UI
    fun launchScraperForLog(item: NotificationLogItem) {
        val serviceIntent = Intent(context, app.mangareader.mobile.ScraperService::class.java).apply {
            putExtra("URL", item.url)
            putExtra("SERIES_TITLE", item.title)
            putExtra("START_CHAPTER", item.startChap)
            putExtra("BASE_CHAPTER", item.baseChap)
            putExtra("MAX_CHAPTERS", item.newCount)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        Toast.makeText(context, "Added ${item.title} to Download Queue!", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        while (true) {
            workerStatus = prefs.getString("worker_status", "Idle") ?: "Idle"

            val jsonString = prefs.getString("notification_log", "[]") ?: "[]"
            try {
                val jsonArray = JSONArray(jsonString)
                val parsedLogs = mutableListOf<NotificationLogItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    parsedLogs.add(
                        NotificationLogItem(
                            title = obj.getString("title"),
                            url = obj.getString("url"),
                            timestamp = obj.getLong("timestamp"),
                            type = obj.getString("type"),
                            newCount = obj.getInt("new_count"),
                            lastLocalName = obj.optString("last_local_name", ""),
                            status = obj.optString("status", "completed"), // Default to completed for old logs
                            startChap = obj.optInt("startChap", 1),
                            baseChap = obj.optInt("baseChap", 1)
                        )
                    )
                }
                logs = parsedLogs.sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            delay(1000)
        }
    }

    val headerGradient = Brush.horizontalGradient(colors = listOf(DarkBlueStart, PurpleEnd))
    val dateFormat = remember { SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()) }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerGradient)
                    .statusBarsPadding()
                    .padding(10.dp)
                    .heightIn(min = 45.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "← Back",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onBackClick() }.padding(8.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = "Notifications Center", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize().background(Color(0xFF121212))) {

            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (hasValidCookie) "✅ Worker Cookie Valid" else "❌ Worker Cookie Missing!",
                        color = if (hasValidCookie) Color.Green else Color.Red,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "Status: $workerStatus",
                        color = Color(0xFFFCDC2A),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    Button(
                        onClick = {
                            if (!hasValidCookie) {
                                Toast.makeText(context, "Cookie is missing!", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            prefs.edit().putString("worker_status", "Triggered manually. Waiting...").apply()

                            val manualRequest = OneTimeWorkRequestBuilder<NotificationWorker>().build()
                            WorkManager.getInstance(context).enqueue(manualRequest)
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        enabled = hasValidCookie
                    ) {
                        Text("Force Check Now")
                    }

                    Button(
                        onClick = {
                            prefs.edit().putLong("last_notification_timestamp", 0L).apply()
                            Toast.makeText(context, "Baseline reset to 0! Next check will act as First Run.", Toast.LENGTH_LONG).show()
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Reset Tracker Baseline (Debug)")
                    }
                }
            }

            if (logs.isEmpty()) {
                Text(
                    text = "No updates detected yet.\nThe background tracker checks every 4 hours.",
                    color = Color.Gray,
                    modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(logs) { log ->
                        Card(
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = log.title,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = dateFormat.format(Date(log.timestamp)),
                                        color = Color.Gray,
                                        fontSize = 12.sp
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                val statusColor = when(log.status) {
                                    "pending" -> Color(0xFFFCDC2A) // Yellow for waiting
                                    "skipped" -> Color.Gray
                                    else -> Color(0xFF4CAF50) // Green for downloaded/completed
                                }

                                if (log.type == "update") {
                                    Text(
                                        text = "⚡ ${log.newCount} New Chapters Available",
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Previous highest chapter: ${log.lastLocalName}",
                                        color = Color.LightGray,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                } else {
                                    Text(
                                        text = "✨ New Series Detected (${log.newCount} Chaps)",
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                }

                                // Interactive Approval Buttons for Pending items
                                if (log.status == "pending") {
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = {
                                                updateLogStatus(log.timestamp, "completed")
                                                launchScraperForLog(log)
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Download", color = Color.White, fontWeight = FontWeight.Bold)
                                        }

                                        Button(
                                            onClick = {
                                                updateLogStatus(log.timestamp, "skipped")
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text("Skip", color = Color.White, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                } else if (log.status == "skipped") {
                                    Text(
                                        text = "Ignored by user",
                                        color = Color.Gray,
                                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}