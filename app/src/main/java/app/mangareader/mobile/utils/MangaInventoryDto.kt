package app.mangareader.mobile.utils

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject

// 1. Data Transfer Object for Supabase
@Serializable
data class MangaInventoryDto(
    val title: String,
    val device_id: String,
    val total_chapters: Int,
    val latest_chapter: String,
    val source_url: String? = null
)

// 2. Device Identity Manager (Reads/Writes .manga_config.json on USBs/Internal Storage)
object DeviceIdentityManager {
    fun getDeviceId(context: Context, rootUri: Uri): String? {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return null
        val file = rootDoc.findFile(".manga_config.json") ?: return null
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { stream ->
                val jsonString = stream.bufferedReader().readText()
                JSONObject(jsonString).optString("device_name", null)
            }
        } catch (e: Exception) { null }
    }

    fun setDeviceId(context: Context, rootUri: Uri, deviceName: String): Boolean {
        val rootDoc = DocumentFile.fromTreeUri(context, rootUri) ?: return false
        var file = rootDoc.findFile(".manga_config.json")
        if (file == null) {
            file = rootDoc.createFile("application/json", ".manga_config.json")
        }
        return try {
            context.contentResolver.openOutputStream(file!!.uri)?.use { stream ->
                // Clean up name for the database
                val cleanName = deviceName.replace(" ", "_")
                val json = JSONObject().put("device_name", cleanName)
                stream.write(json.toString().toByteArray())
            }
            true
        } catch (e: Exception) { false }
    }
}

// 3. Supabase Client & Sync Logic
object SupabaseManager {
    // Replace with your actual URL and Anon Key
    private const val SUPABASE_URL = "https://nhocakuvhrapyamueykb.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5ob2Nha3V2aHJhcHlhbXVleWtiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NDY1OTgsImV4cCI6MjA5NDUyMjU5OH0.swivbTfevFXs2Sw3Ont4ZOQJ833iZ2RuHW7_8QtzMZM"

    val client = createSupabaseClient(SUPABASE_URL, SUPABASE_KEY) {
        install(Postgrest)
    }

    /**
     * Reads the local JSON tracker via FileUtils and pushes it to Supabase.
     */
    suspend fun syncLocalTrackerToCloud(context: Context, rootUri: Uri, deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Fetch existing tracker parsed from manga_tracker.json
                val localTracker = FileUtils.getTracker(context, rootUri)
                if (localTracker.isEmpty()) return@withContext true

                // Map local tracker format to Supabase DTO
                val dtos = localTracker.map { entry ->
                    MangaInventoryDto(
                        title = entry.title,
                        device_id = deviceId,
                        total_chapters = entry.lastChapterNumber,
                        latest_chapter = entry.lastChapterName,
                        source_url = entry.url.takeIf { it.isNotEmpty() }
                    )
                }

                // UPSERT operation (inserts new, updates existing based on Composite Key)
                client.postgrest["manga_inventory"].upsert(dtos)
                true
            } catch (e: Exception) {
                Log.e("SupabaseSync", "Failed to sync to cloud", e)
                false
            }
        }
    }

    /**
     * Call this from ScraperService when a download completes.
     * It fails silently if the device hasn't been named yet (since background tasks can't show popups).
     */
    suspend fun autoSyncBackground(context: Context, rootUri: Uri) {
        val deviceId = DeviceIdentityManager.getDeviceId(context, rootUri)
        if (deviceId != null) {
            syncLocalTrackerToCloud(context, rootUri, deviceId)
            Log.d("SupabaseSync", "Background Auto-Sync completed for $deviceId")
        } else {
            Log.w("SupabaseSync", "Auto-Sync skipped: Drive not named yet.")
        }
    }
}

// 4. Compose UI Components for HomeScreen
@Composable
fun DeviceNameDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name this Drive/Device") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("e.g. Mobile_Internal, USB_32GB") },
                singleLine = true
            )
        },
        confirmButton = {
            Button(onClick = { if (text.isNotBlank()) onSave(text) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun CloudSyncButton(rootUri: Uri?, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var isSyncing by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog && rootUri != null) {
        DeviceNameDialog(
            onDismiss = { showDialog = false },
            onSave = { name ->
                DeviceIdentityManager.setDeviceId(context, rootUri, name)
                showDialog = false

                // Proceed to sync immediately after naming
                scope.launch {
                    isSyncing = true
                    val success = SupabaseManager.syncLocalTrackerToCloud(context, rootUri, name)
                    isSyncing = false
                    Toast.makeText(context, if(success) "Sync Complete!" else "Sync Failed", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Box(
        modifier = modifier.clickable {
            if (rootUri == null) {
                Toast.makeText(context, "Select a Manga folder first!", Toast.LENGTH_SHORT).show()
                return@clickable
            }

            // 1. Check if we know what device this is
            val deviceId = DeviceIdentityManager.getDeviceId(context, rootUri)

            if (deviceId == null) {
                // Prompt user to name it
                showDialog = true
            } else {
                // We know the device, proceed to sync
                scope.launch {
                    isSyncing = true
                    val success = SupabaseManager.syncLocalTrackerToCloud(context, rootUri, deviceId)
                    isSyncing = false
                    Toast.makeText(context, if(success) "Cloud Synced!" else "Sync Failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    ) {
        if (isSyncing) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
        } else {
            Text(text = "☁️", fontSize = 24.sp)
        }
    }
}