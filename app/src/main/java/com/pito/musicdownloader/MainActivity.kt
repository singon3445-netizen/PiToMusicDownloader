package com.pito.musicdownloader

import android.app.DownloadManager
import android.content.ContentValues
import android.provider.MediaStore
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private data class Track(val id: String, val title: String, val duration: String, val videoUrl: String)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MainScreen(this) } }
    }
}

@Composable
private fun MainScreen(context: Context) {
    var link by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var audioUrl by remember { mutableStateOf("") }
    var audioTitle by remember { mutableStateOf("") }
    var conversionBusy by remember { mutableStateOf(false) }
    var sourceAudio by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf("Pega un enlace de YouTube para identificar el vídeo o la playlist.") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { sourceAudio = uri; status = "Archivo seleccionado. Pulsa Convertir a MP3." }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("PiTo Music Downloader", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Pega un enlace de YouTube", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = link, onValueChange = { link = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Enlace de vídeo o playlist") }, placeholder = { Text("https://www.youtube.com/watch?v=…") }, singleLine = true)
        OutlinedTextField(value = apiKey, onValueChange = { apiKey = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Clave de YouTube Data API") }, placeholder = { Text("Necesaria para títulos y duración") }, singleLine = true)
        Button(enabled = !loading, onClick = {
            val parsed = parseYoutubeLink(link.trim())
            if (parsed == null) {
                status = "Enlace no reconocido. Pega una URL de youtube.com o youtu.be."
                tracks = emptyList(); selected = emptySet()
            } else if (apiKey.isBlank()) {
                status = "Enlace reconocido como " + (if (parsed.first != null) "playlist" else "vídeo individual") + ". Para cargar títulos y duración, introduce una clave de YouTube Data API."
            } else {
                loading = true; status = "Cargando información de YouTube…"; tracks = emptyList(); selected = emptySet()
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) { loadTracks(parsed.first, parsed.second, apiKey.trim()) }
                        tracks = result; selected = result.map { it.id }.toSet()
                        status = if (result.isEmpty()) "No se encontraron vídeos públicos en ese enlace." else "Se encontraron ${result.size} vídeo(s). Marca los que quieras seleccionar."
                    } catch (e: Exception) {
                        status = "No se pudo analizar el enlace. Comprueba la clave API, la conexión y que el contenido sea público. ${e.message ?: "Error de red"}"
                    } finally { loading = false }
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Analizando…" else "Analizar enlace") }

        if (tracks.isNotEmpty()) {
            Text("Vídeos encontrados: ${tracks.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Seleccionados: ${selected.size}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { selected = tracks.map { it.id }.toSet() }) { Text("Seleccionar todos") }
                TextButton(onClick = { selected = emptySet() }) { Text("Ninguno") }
            }
            tracks.forEach { track ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = track.id in selected, onCheckedChange = { checked -> selected = if (checked) selected + track.id else selected - track.id })
                    Column(Modifier.weight(1f)) {
                        Text(track.title, fontWeight = FontWeight.Medium)
                        Text(track.duration, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(track.videoUrl))) }) { Text("Abrir en YouTube") }
                    }
                }
                HorizontalDivider()
            }
            Text("La selección organiza los vídeos; esta versión no extrae ni descarga audio de YouTube.", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Text("Descarga individual de audio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = audioTitle, onValueChange = { audioTitle = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nombre del archivo (opcional)") }, singleLine = true)
        OutlinedTextField(value = audioUrl, onValueChange = { audioUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("URL HTTPS directa del audio") }, placeholder = { Text("Enlace directo a un archivo de audio autorizado") }, singleLine = true)
        Button(onClick = {
            try {
                val url = audioUrl.trim(); val uri = Uri.parse(url)
                if (uri.scheme != "https" || uri.host.isNullOrBlank() || !URLUtil.isNetworkUrl(url) || uri.host!!.lowercase() in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be")) status = "Introduce una URL HTTPS directa a un archivo autorizado, no una página de YouTube."
                else {
                    val guessed = URLUtil.guessFileName(url, null, null).substringAfterLast("/").ifBlank { "audio.mp3" }
                    val baseName = sanitizeFileName(audioTitle.trim().ifBlank { guessed.substringBeforeLast('.', guessed) })
                    val extension = guessed.substringAfterLast('.', "mp3").takeIf { it.matches(Regex("[A-Za-z0-9]{2,5}")) } ?: "mp3"
                    val safeName = "$baseName.$extension"
                    val request = DownloadManager.Request(uri).setTitle(safeName).setDescription("PiTo Music Downloader").setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED).setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "PiTo Music Downloader/$safeName").setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
                    status = "Descarga iniciada: Música/PiTo Music Downloader/$safeName"
                }
            } catch (_: Exception) { status = "No se pudo iniciar la descarga. Comprueba la URL e inténtalo de nuevo." }
        }, modifier = Modifier.fillMaxWidth()) { Text("Descargar audio individual") }

        HorizontalDivider()
        Text("Convertir archivo local a MP3", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Elige un archivo guardado en el teléfono. La conversión se realiza localmente; no convierte enlaces de YouTube.", style = MaterialTheme.typography.bodySmall)
        Button(enabled = !conversionBusy, onClick = { pickMedia.launch(arrayOf("audio/*", "video/*")) }, modifier = Modifier.fillMaxWidth()) { Text("Elegir audio o vídeo") }
        Text(sourceAudio?.lastPathSegment ?: "Ningún archivo seleccionado", style = MaterialTheme.typography.bodySmall)
        Button(enabled = sourceAudio != null && !conversionBusy, onClick = {
            val inputUri = sourceAudio
            if (inputUri == null) status = "Primero elige un archivo."
            else {
                conversionBusy = true; status = "Convirtiendo a MP3…"
                scope.launch {
                    try {
                        val output = withContext(Dispatchers.IO) {
                            val inputFile = File(context.cacheDir, "pito_input_${System.currentTimeMillis()}")
                            context.contentResolver.openInputStream(inputUri).use { input ->
                                if (input == null) throw IllegalStateException("No se pudo abrir el archivo seleccionado")
                                inputFile.outputStream().use { outputStream -> input.copyTo(outputStream) }
                            }
                            val outputFile = File(context.cacheDir, "pito_${System.currentTimeMillis()}.mp3")
                            val command = "-y -i '${inputFile.absolutePath}' -vn -codec:a libmp3lame -q:a 2 '${outputFile.absolutePath}'"
                            val session = FFmpegKit.execute(command)
                            inputFile.delete()
                            if (!ReturnCode.isSuccess(session.returnCode) || !outputFile.exists() || outputFile.length() == 0L) {
                                outputFile.delete()
                                throw IllegalStateException(session.failStackTrace ?: "Formato no compatible o error de conversión")
                            }
                            outputFile
                        }
                        withContext(Dispatchers.IO) {
                            val values = ContentValues().apply {
                                put(MediaStore.Audio.Media.DISPLAY_NAME, "${sanitizeFileName(audioTitle.ifBlank { "PiTo-convertido" })}.mp3")
                                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/PiTo Music Downloader")
                            }
                            val destination = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: throw IllegalStateException("No se pudo crear el archivo de destino")
                            try {
                                context.contentResolver.openOutputStream(destination).use { stream ->
                                    if (stream == null) throw IllegalStateException("No se pudo guardar el MP3")
                                    output.inputStream().use { it.copyTo(stream) }
                                }
                            } catch (e: Exception) { context.contentResolver.delete(destination, null, null); throw e }
                            output.delete()
                        }
                        status = "Conversión completada. El MP3 está en Música/PiTo Music Downloader."
                    } catch (e: Exception) {
                        status = "No se pudo convertir el archivo: ${e.message?.take(180) ?: "error desconocido"}"
                    } finally { conversionBusy = false }
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (conversionBusy) "Convirtiendo…" else "Convertir a MP3") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text("La API oficial de YouTube permite consultar metadatos, pero no descargar audio de vídeos. Usa descargas directas solo con archivos cuya descarga tengas autorizada.", style = MaterialTheme.typography.bodySmall)
    }
}

private fun parseYoutubeLink(raw: String): Pair<String?, String?>? {
    return try {
        val uri = Uri.parse(raw); val host = uri.host?.lowercase() ?: return null
        if (host !in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "www.youtu.be")) return null
        val playlistId = uri.getQueryParameter("list")
        val videoId = if (host.endsWith("youtu.be")) uri.lastPathSegment else uri.getQueryParameter("v")
        if (!playlistId.isNullOrBlank()) Pair(playlistId, null)
        else if (!videoId.isNullOrBlank() && videoId.matches(Regex("[A-Za-z0-9_-]{6,}"))) Pair(null, videoId)
        else null
    } catch (_: Exception) { null }
}

private fun loadTracks(playlistId: String?, videoId: String?, key: String): List<Track> {
    if (videoId != null) {
        val json = getJson("https://www.googleapis.com/youtube/v3/videos?part=snippet,contentDetails&id=$videoId&key=$key")
        val items = json.getJSONArray("items"); if (items.length() == 0) return emptyList()
        val item = items.getJSONObject(0); val id = item.getString("id")
        return listOf(Track(id, item.getJSONObject("snippet").optString("title", "Vídeo"), formatDuration(item.getJSONObject("contentDetails").optString("duration", "")), "https://www.youtube.com/watch?v=$id"))
    }
    val result = mutableListOf<Track>(); var pageToken: String? = null
    do {
        val tokenParam = if (pageToken.isNullOrBlank()) "" else "&pageToken=$pageToken"
        val json = getJson("https://www.googleapis.com/youtube/v3/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=$playlistId$tokenParam&key=$key")
        val items = json.getJSONArray("items")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i); val content = item.optJSONObject("contentDetails") ?: continue
            val id = content.optString("videoId", ""); if (id.isBlank()) continue
            val title = item.optJSONObject("snippet")?.optString("title", "Vídeo") ?: "Vídeo"
            result.add(Track(id, title, "Cargando duración…", "https://www.youtube.com/watch?v=$id"))
        }
        pageToken = json.optString("nextPageToken", "").takeIf { it.isNotBlank() && it != "null" }
    } while (pageToken != null && result.size < 500)
    result.map { it.id }.distinct().chunked(50).forEach { chunk ->
        val json = getJson("https://www.googleapis.com/youtube/v3/videos?part=contentDetails&id=${chunk.joinToString(",")}&key=$key")
        val items = json.getJSONArray("items")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i); val id = item.getString("id")
            val duration = formatDuration(item.getJSONObject("contentDetails").optString("duration", ""))
            val index = result.indexOfFirst { it.id == id }
            if (index >= 0) result[index] = result[index].copy(duration = duration)
        }
    }
    return result
}

private fun getJson(address: String): JSONObject {
    val connection = URL(address).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"; connection.connectTimeout = 15000; connection.readTimeout = 20000
    try {
        val code = connection.responseCode; val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val message = try { JSONObject(body).getJSONObject("error").optString("message", "HTTP $code") } catch (_: Exception) { "HTTP $code" }
            throw IllegalStateException(message)
        }
        return JSONObject(body)
    } finally { connection.disconnect() }
}

private fun formatDuration(value: String): String {
    val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?").matchEntire(value) ?: return "Duración no disponible"
    val hours = match.groupValues[1].toIntOrNull() ?: 0
    val minutes = match.groupValues[2].toIntOrNull() ?: 0
    val seconds = match.groupValues[3].toIntOrNull() ?: 0
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

private fun sanitizeFileName(value: String): String =
    value.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(100).ifBlank { "audio" }
