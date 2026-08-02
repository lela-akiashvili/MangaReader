package app.designsystem.component

import android.graphics.Bitmap.Config
import android.graphics.BitmapFactory
import android.graphics.BitmapFactory.Options
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.designsystem.theme.YellowAccent
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AdaptiveWebtoonImage(uri: Uri) {
    val context = LocalContext.current
    var imageChunks by remember { mutableStateOf<List<Rect>?>(null) }
    var originalSize by remember { mutableStateOf(Pair(0, 0)) }

    LaunchedEffect(uri) {
        withContext(Dispatchers.IO) {
            try {
                val options = Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
                val height = options.outHeight
                val width = options.outWidth
                originalSize = Pair(width, height)

                if (height > 4000) {
                    val chunks = mutableListOf<Rect>()
                    var top = 0
                    while (top < height) {
                        val bottom = (top + 4000).coerceAtMost(height)
                        chunks.add(Rect(0, top, width, bottom))
                        top += 4000
                    }
                    imageChunks = chunks
                } else {
                    imageChunks = emptyList()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                imageChunks = emptyList()
            }
        }
    }

    if (imageChunks == null) {
        Box(modifier = Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = YellowAccent)
        }
    } else if (imageChunks!!.isEmpty()) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
    } else {
        Column(modifier = Modifier.fillMaxWidth()) {
            imageChunks!!.forEach { rect ->
                ChunkRenderer(uri = uri, rect = rect, width = originalSize.first)
            }
        }
    }
}

@Composable
private fun ChunkRenderer(uri: Uri, rect: Rect, width: Int) {
    val context = LocalContext.current
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    val aspectRatio = width.toFloat() / rect.height().toFloat()

    LaunchedEffect(uri, rect) {
        withContext(Dispatchers.IO) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val decoder = if (VERSION.SDK_INT >= VERSION_CODES.S) {
                        BitmapRegionDecoder.newInstance(stream)
                    } else {
                        @Suppress("DEPRECATION")
                        BitmapRegionDecoder.newInstance(stream, false)
                    }
                    decoder?.let {
                        val options = Options().apply { inPreferredConfig = Config.ARGB_8888 }
                        val bmp = it.decodeRegion(rect, options)
                        it.recycle()
                        withContext(Dispatchers.Main) { bitmap = bmp?.asImageBitmap() }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    Box(modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.FillWidth
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.LightGray, strokeWidth = 2.dp)
            }
        }
    }
}
