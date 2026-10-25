package dev.trmx.gui.ui

/*
 * Pure Compose remote image loader with in-memory caching.
 * Uses OkHttp and Android BitmapFactory to decode web images and avatars
 * without pulling in heavyweight third-party libraries.
 */

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.trmx.gui.ui.Tokens.Palette as P
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object ImageCache {
    val memoryCache = ConcurrentHashMap<String, Bitmap>()
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    if (url.isNullOrBlank()) {
        Box(
            modifier = modifier.background(P.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = P.TextMuted, modifier = Modifier.size(20.dp))
        }
        return
    }

    var bitmap by remember(url) { mutableStateOf(ImageCache.memoryCache[url]) }
    var isLoading by remember(url) { mutableStateOf(bitmap == null) }

    LaunchedEffect(url) {
        if (bitmap == null) {
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("User-Agent", "Mozilla/5.0")
                        .build()
                    val response = ImageCache.httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { stream ->
                            val decoded = BitmapFactory.decodeStream(stream)
                            if (decoded != null) {
                                ImageCache.memoryCache[url] = decoded
                                bitmap = decoded
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Graceful fallback to placeholder on network issue
                } finally {
                    isLoading = false
                }
            }
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale
        )
    } else if (isLoading) {
        Box(
            modifier = modifier.background(P.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(
                color = P.BorderStrong,
                strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp)
            )
        }
    } else {
        Box(
            modifier = modifier.background(P.SurfaceHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null, tint = P.TextMuted, modifier = Modifier.size(20.dp))
        }
    }
}
