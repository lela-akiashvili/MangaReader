package app.mangareader.mobile.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.documentfile.provider.DocumentFile
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.mangareader.mobile.workers.NotificationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

    var showJsonEditor by remember { mutableStateOf(false) }
    var jsonTextFieldValue by remember { mutableStateOf(TextFieldValue("")) }

    // --- USB DATABASE HELPERS ---
    fun readTrackerJson(): String {
        val rootUriStr = prefs.getString("last_root_uri", "") ?: ""
        if (rootUriStr.isEmpty()) return "[\n]"
        return try {
            val rootUri = Uri.parse(rootUriStr)
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            val file = rootDoc?.findFile("manga_tracker.json")
            if (file != null) {
                val rawJson = context.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() } ?: "[\n]"
                try { JSONArray(rawJson).toString(4) } catch (e: Exception) { rawJson }
            } else "[\n]"
        } catch(e: Exception) { "[\n]" }
    }

    fun writeTrackerJson(json: String): Boolean {
        val rootUriStr = prefs.getString("last_root_uri", "") ?: ""
        if (rootUriStr.isEmpty()) return false
        return try {
            val rootUri = Uri.parse(rootUriStr)
            val rootDoc = DocumentFile.fromTreeUri(context, rootUri)
            var file = rootDoc?.findFile("manga_tracker.json")
            if (file == null) file = rootDoc?.createFile("application/json", "manga_tracker.json")
            if (file != null) {
                context.contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(json.toByteArray()) }
                true
            } else false
        } catch(e: Exception) { false }
    }

    fun readPendingLogs(): String {
        val rootUriStr = prefs.getString("last_root_uri", "") ?: ""
        if (rootUriStr.isEmpty()) return "[]"
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(rootUriStr))
            val file = rootDoc?.findFile("pending_updates.json")
            file?.let {
                context.contentResolver.openInputStream(it.uri)?.bufferedReader()?.use { reader -> reader.readText() }
            } ?: "[]"
        } catch (e: Exception) { "[]" }
    }

    fun writePendingLogs(json: String): Boolean {
        val rootUriStr = prefs.getString("last_root_uri", "") ?: ""
        if (rootUriStr.isEmpty()) return false
        return try {
            val rootDoc = DocumentFile.fromTreeUri(context, Uri.parse(rootUriStr))
            var file = rootDoc?.findFile("pending_updates.json")
            if (file == null) file = rootDoc?.createFile("application/json", "pending_updates.json")
            file?.uri?.let { uri ->
                context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) }
                true
            } ?: false
        } catch (e: Exception) { false }
    }

    fun updateLogStatus(timestamp: Long, newStatus: String) {
        val jsonString = readPendingLogs()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                if (obj.getLong("timestamp") == timestamp) {
                    obj.put("status", newStatus)
                    break
                }
            }
            writePendingLogs(jsonArray.toString(4))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun launchScraperForLog(item: NotificationLogItem) {
        val serviceIntent = Intent(context, app.mangareader.mobile.ScraperService::class.java).apply {
            putExtra("URL", item.url)
            putExtra("SERIES_TITLE", item.title)
            putExtra("START_CHAPTER", item.startChap)
            putExtra("BASE_CHAPTER", item.baseChap)
            putExtra("MAX_CHAPTERS", item.newCount)
            putExtra("IS_AUTO_UPDATE", true)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
        Toast.makeText(context, "Added ${item.title} to Download Queue!", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(Unit) {
        // Runs in IO to ensure reading from USB doesn't cause UI stuttering
        withContext(Dispatchers.IO) {
            while (true) {
                val wStatus = prefs.getString("worker_status", "Idle") ?: "Idle"

                val jsonString = readPendingLogs()
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
                                status = obj.optString("status", "completed"),
                                startChap = obj.optInt("startChap", 1),
                                baseChap = obj.optInt("baseChap", 1)
                            )
                        )
                    }

                    withContext(Dispatchers.Main) {
                        workerStatus = wStatus
                        logs = parsedLogs.sortedByDescending { it.timestamp }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                delay(1000)
            }
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
                            val text = readTrackerJson()
                            jsonTextFieldValue = TextFieldValue(text)
                            showJsonEditor = true
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                    ) {
                        Text("Edit Tracker JSON (Advanced)")
                    }

                    Button(
                        onClick = {
                            prefs.edit()
                                .putLong("last_notification_timestamp", 0L)
                                .remove("notification_log") // Cleanup old memory if any exists
                                .apply()

                            // NEW: Erase the USB pending logs file
                            writePendingLogs("[]")
                            Toast.makeText(context, "Baseline reset & USB logs cleared! Next check will act as First Run.", Toast.LENGTH_LONG).show()
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
                                    "pending" -> Color(0xFFFCDC2A)
                                    "skipped" -> Color.Gray
                                    else -> Color(0xFF4CAF50)
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

    if (showJsonEditor) {
        var searchQuery by remember { mutableStateOf("") }
        var matchIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
        var currentMatchIndex by remember { mutableIntStateOf(-1) }

        LaunchedEffect(searchQuery, jsonTextFieldValue.text) {
            if (searchQuery.isEmpty()) {
                matchIndices = emptyList()
                currentMatchIndex = -1
            } else {
                val indices = mutableListOf<Int>()
                var index = jsonTextFieldValue.text.indexOf(searchQuery, ignoreCase = true)
                while (index >= 0) {
                    indices.add(index)
                    index = jsonTextFieldValue.text.indexOf(searchQuery, index + 1, ignoreCase = true)
                }
                matchIndices = indices
                if (currentMatchIndex >= matchIndices.size) {
                    currentMatchIndex = if (matchIndices.isNotEmpty()) 0 else -1
                }
            }
        }

        fun scrollToMatch(index: Int) {
            if (matchIndices.isNotEmpty() && index in matchIndices.indices) {
                currentMatchIndex = index
                val startIndex = matchIndices[index]
                jsonTextFieldValue = jsonTextFieldValue.copy(
                    selection = TextRange(startIndex, startIndex + searchQuery.length)
                )
            }
        }

        AlertDialog(
            onDismissRequest = { showJsonEditor = false },
            title = { Text("Raw Tracker JSON", color = Color.White) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        placeholder = { Text("Search by Title or URL...", color = Color.Gray) },
                        singleLine = true,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF1E1E1E),
                            unfocusedContainerColor = Color(0xFF1E1E1E),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedIndicatorColor = Color(0xFF2196F3)
                        ),
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (matchIndices.isNotEmpty()) {
                                    Text(
                                        text = "${currentMatchIndex + 1}/${matchIndices.size}",
                                        color = Color.Gray,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                    IconButton(onClick = {
                                        val prev = if (currentMatchIndex - 1 < 0) matchIndices.size - 1 else currentMatchIndex - 1
                                        scrollToMatch(prev)
                                    }) { Text("↑", color = Color.White, fontWeight = FontWeight.Bold) }
                                    IconButton(onClick = {
                                        val next = if (currentMatchIndex + 1 >= matchIndices.size) 0 else currentMatchIndex + 1
                                        scrollToMatch(next)
                                    }) { Text("↓", color = Color.White, fontWeight = FontWeight.Bold) }
                                } else if (searchQuery.isNotEmpty()) {
                                    Text("0/0", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
                                }
                            }
                        }
                    )

                    OutlinedTextField(
                        value = jsonTextFieldValue,
                        onValueChange = { jsonTextFieldValue = it },
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color(0xFF121212),
                            unfocusedContainerColor = Color(0xFF121212),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        try {
                            val formattedJson = JSONArray(jsonTextFieldValue.text).toString(4)

                            if (writeTrackerJson(formattedJson)) {
                                Toast.makeText(context, "Tracker saved successfully!", Toast.LENGTH_SHORT).show()
                                showJsonEditor = false
                            } else {
                                Toast.makeText(context, "Failed to save file.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Invalid JSON format! Must be an array.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Text("Save Changes")
                }
            },
            dismissButton = {
                TextButton(onClick = { showJsonEditor = false }) { Text("Cancel", color = Color.White) }
            },
            containerColor = Color(0xFF1E1E1E),
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
        )
    }
}