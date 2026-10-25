package dev.trmx.gui.ui

/*
 * Pure Compose remote image loader with in-memory caching.
 * Uses OkHttp and Android BitmapFactory to decode web images and avatars
 * without pulling in heavyweight third-party libraries.
 *
 * Employs standard browser headers so Instagram CDN images load reliably.
 * Provides initial-letter fallback circle (first letter of the name) on failure
 * or missing URL, so avatars never render a broken-image placeholder.
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.trmx.gui.download.HtmlDecoder
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
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

@Composable
fun RemoteImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackText: String? = null
) {
    val cleanUrl = url?.let { HtmlDecoder.decode(it).trim() }?.ifBlank { null }

    if (cleanUrl == null) {
        if (!fallbackText.isNullOrBlank()) {
            InitialLetterFallback(fallbackText, modifier)
        } else {
            Box(
                modifier = modifier.background(P.SurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = P.TextMuted, modifier = Modifier.size(20.dp))
            }
        }
        return
    }

    var bitmap by remember(cleanUrl) { mutableStateOf(ImageCache.memoryCache[cleanUrl]) }
    var isLoading by remember(cleanUrl) { mutableStateOf(bitmap == null) }

    LaunchedEffect(cleanUrl) {
        if (bitmap == null) {
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(cleanUrl)
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36")
                        .header("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .header("Sec-Fetch-Dest", "image")
                        .header("Sec-Fetch-Mode", "no-cors")
                        .header("Sec-Fetch-Site", "cross-site")
                        .header("Referer", "https://www.instagram.com/")
                        .build()
                    val response = ImageCache.httpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        response.body?.byteStream()?.use { stream ->
                            val decoded = BitmapFactory.decodeStream(stream)
                            if (decoded != null) {
                                ImageCache.memoryCache[cleanUrl] = decoded
                                bitmap = decoded
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Graceful fallback to initial circle on network issue
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
        if (!fallbackText.isNullOrBlank()) {
            InitialLetterFallback(fallbackText, modifier)
        } else {
            Box(
                modifier = modifier.background(P.SurfaceHigh),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = P.TextMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun InitialLetterFallback(
    text: String,
    modifier: Modifier = Modifier
) {
    val initial = text.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    Box(
        modifier = modifier.background(P.SurfaceHigh),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial.toString(),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = P.Accent
        )
    }
}
