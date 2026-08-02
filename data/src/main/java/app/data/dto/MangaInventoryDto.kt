package app.data.dto

import android.content.Context
import android.net.Uri
import android.net.http.HttpResponseCache.install
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.data.datasource.local.FileUtils
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONObject

@Serializable
data class MangaInventoryDto(
    val title: String,
    val device_id: String,
    val total_chapters: Int,
    val latest_chapter: String,
    val source_url: String? = null
)

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
                val cleanName = deviceName.replace(" ", "_")
                val json = JSONObject().put("device_name", cleanName)
                stream.write(json.toString().toByteArray())
            }
            true
        } catch (e: Exception) { false }
    }
}

object SupabaseManager {
    private const val SUPABASE_URL = "https://nhocakuvhrapyamueykb.supabase.co"
    private const val SUPABASE_KEY = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im5ob2Nha3V2aHJhcHlhbXVleWtiIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzg5NDY1OTgsImV4cCI6MjA5NDUyMjU5OH0.swivbTfevFXs2Sw3Ont4ZOQJ833iZ2RuHW7_8QtzMZM"

    val client = createSupabaseClient(SUPABASE_URL, SUPABASE_KEY) {
        install(Postgrest)
    }

    suspend fun syncLocalTrackerToCloud(context: Context, rootUri: Uri, deviceId: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val localTracker = FileUtils.getTracker(context, rootUri)
                if (localTracker.isEmpty()) return@withContext true

                val dtos = localTracker.map { entry ->
                    MangaInventoryDto(
                        title = entry.title,
                        device_id = deviceId,
                        total_chapters = entry.lastChapterNumber,
                        latest_chapter = entry.lastChapterName,
                        source_url = entry.url.takeIf { it.isNotEmpty() }
                    )
                }

                client.postgrest["manga_inventory"].upsert(dtos)
                true
            } catch (e: Exception) {
                Log.e("SupabaseSync", "Failed to sync to cloud", e)
                false
            }
        }
    }

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