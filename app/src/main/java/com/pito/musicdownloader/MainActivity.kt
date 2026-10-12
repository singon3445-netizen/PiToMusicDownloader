package com.pito.musicdownloader

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.URLUtil
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MainScreen(this) } }
    }
}

@Composable
private fun MainScreen(context: Context) {
    var playlistUrl by remember { mutableStateOf("") }
    var audioUrl by remember { mutableStateOf("") }
    var fileName by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Los archivos descargados se guardarán en Música/PiTo Music Downloader.") }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("PiTo Music Downloader", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Audio · Android", style = MaterialTheme.typography.titleMedium)
        Text("Playlist de YouTube", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = playlistUrl,
            onValueChange = { playlistUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Pega el enlace de la playlist") },
            placeholder = { Text("https://www.youtube.com/playlist?list=…") },
            singleLine = true
        )
        Button(onClick = {
            val valid = playlistUrl.startsWith("https://www.youtube.com/") || playlistUrl.startsWith("https://youtu.be/")
            status = if (!valid) "Pega un enlace válido de YouTube." else "El enlace parece válido. Esta versión inicial no extrae audio de YouTube: la API oficial no proporciona archivos MP3. Puedes descargar audio directo autorizado en el apartado siguiente."
        }, modifier = Modifier.fillMaxWidth()) { Text("Comprobar enlace") }

        HorizontalDivider()
        Text("Descargar un archivo de audio autorizado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(value = audioUrl, onValueChange = { audioUrl = it }, modifier = Modifier.fillMaxWidth(), label = { Text("URL directa del archivo de audio") }, singleLine = true)
        OutlinedTextField(value = fileName, onValueChange = { fileName = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Nombre del archivo (opcional)") }, placeholder = { Text("cancion.mp3") }, singleLine = true)
        Button(onClick = {
            try {
                val uri = Uri.parse(audioUrl.trim())
                val schemeOk = uri.scheme == "https" && !uri.host.isNullOrBlank()
                if (!schemeOk || !URLUtil.isNetworkUrl(audioUrl.trim())) {
                    status = "Introduce una URL HTTPS directa a un archivo de audio autorizado."
                } else {
                    val guessed = fileName.trim().ifBlank { URLUtil.guessFileName(audioUrl.trim(), null, "audio/mpeg") }
                    val safeName = if (guessed.contains('.')) guessed else "$guessed.mp3"
                    val request = DownloadManager.Request(uri)
                        .setTitle(safeName)
                        .setDescription("PiTo Music Downloader")
                        .setMimeType("audio/mpeg")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalPublicDir(Environment.DIRECTORY_MUSIC, "PiTo Music Downloader/$safeName")
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(request)
                    status = "Descarga añadida. Al terminar, el archivo estará en Música/PiTo Music Downloader."
                }
            } catch (e: Exception) {
                status = "No se pudo iniciar la descarga. Comprueba la URL y vuelve a intentarlo."
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Descargar en Música") }
        Text(status, style = MaterialTheme.typography.bodyMedium)
        Text("Nota: pegar el enlace de una playlist no descarga automáticamente su audio. Esta base no evita restricciones de YouTube ni convierte enlaces de vídeo a MP3.", style = MaterialTheme.typography.bodySmall)
    }
}
