// File preserved for architectural reference but unreachable from UI

import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.net.toFile
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ai.SuperImageUpscaler
import org.koitharu.kotatsu.core.ai.model.UpscaleProgress
import org.koitharu.kotatsu.core.ai.model.UpscaleStatusProvider
import org.koitharu.kotatsu.core.image.BitmapDecoderCompat
import org.koitharu.kotatsu.core.util.ext.compressToPNG
import org.koitharu.kotatsu.core.util.ext.isZipUri
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking

import kotlinx.coroutines.flow.MutableSharedFlow
import org.koitharu.kotatsu.local.data.LocalStorageChanges
import org.koitharu.kotatsu.local.data.input.LocalMangaParser
import org.koitharu.kotatsu.local.domain.model.LocalManga

@HiltWorker
class UpscaleWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val upscaler: SuperImageUpscaler,
    private val statusProvider: UpscaleStatusProvider,
    @LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalManga?>,
) : CoroutineWorker(appContext, params) {

    private val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val factor = inputData.getInt("factor", 4)
        val uriString = inputData.getString("uri") ?: return@withContext Result.failure()
        val mangaId = inputData.getLong("manga_id", 0L)
        val chapterId = inputData.getLong("chapter_id", 0L)
        val uri = Uri.parse(uriString)
        val notificationId = uriString.hashCode()
        
        val builder = NotificationCompat.Builder(applicationContext, "download")
            .setContentTitle(applicationContext.getString(R.string.ai_upscaling))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setOnlyAlertOnce(true)
        
        notificationManager.notify(notificationId, builder.build())
        
        try {
            statusProvider.updateProgress(UpscaleProgress(mangaId, chapterId, 0, 0, 0, 0, factor, 0, UpscaleProgress.Status.INITIALIZING))
            
            if (uri.isZipUri() || uriString.endsWith(".cbz")) {
                upscaleZip(uri, factor, builder, notificationId, mangaId, chapterId)
            } else {
                upscaleDirectory(uri, factor, builder, notificationId, mangaId, chapterId)
            }
            
            statusProvider.updateProgress(UpscaleProgress(mangaId, chapterId, 1, 1, 1, 1, factor, 0, UpscaleProgress.Status.COMPLETED))
            
            builder.setContentTitle("Upscaling Complete")
                .setContentText("Factor: ${factor}x")
                .setProgress(0, 0, false)
                .setOngoing(false)
            notificationManager.notify(notificationId, builder.build())
            
            return@withContext Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            statusProvider.updateProgress(UpscaleProgress(mangaId, chapterId, 0, 0, 0, 0, factor, 0, UpscaleProgress.Status.FAILED, e.message))
             builder.setContentTitle("Upscaling Failed")
                .setContentText(e.message)
                .setProgress(0, 0, false)
                .setOngoing(false)
            notificationManager.notify(notificationId, builder.build())
            return@withContext Result.failure()
        }
    }

    private suspend fun upscaleZip(uri: Uri, factor: Int, builder: NotificationCompat.Builder, notificationId: Int, mangaId: Long, chapterId: Long) {
        val path = if (uri.scheme == "zip") uri.schemeSpecificPart.substringBefore("!") else uri.path!!
        val file = File(path)
        val tempDir = File(applicationContext.cacheDir, "upscale_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        val zipFile = ZipFile(file)
        val entries = zipFile.entries().toList().filter { !it.isDirectory && isImage(it.name) }
        val total = entries.size
        
        builder.setProgress(total, 0, false)
        notificationManager.notify(notificationId, builder.build())
        
        val startTime = System.currentTimeMillis()
        entries.forEachIndexed { index, entry ->
             if (isStopped) return@forEachIndexed
             
             builder.setProgress(total, index + 1, false)
             notificationManager.notify(notificationId, builder.build())
             
             val inputStream = zipFile.getInputStream(entry)
             val bitmap = BitmapDecoderCompat.decode(inputStream, null)
             inputStream.close()
             
             if (bitmap != null) {
                 val upscaled = upscaler.upscale(bitmap, factor) { parts: Int, pTotal: Int ->
                     val elapsed = System.currentTimeMillis() - startTime
                     val avgPerPage = if (index > 0) elapsed / index else (elapsed / (parts.toFloat() / pTotal)).toLong()
                     val timeLeft = (total - index) * (avgPerPage / 1000)
                     
                     runBlocking {
                         statusProvider.updateProgress(UpscaleProgress(
                             mangaId, chapterId, total, index, parts, pTotal, factor, timeLeft, UpscaleProgress.Status.PROCESSING
                         ))
                     }
                 }
                 bitmap.recycle()
                 
                 if (upscaled != null) {
                     val outFile = File(tempDir, entry.name)
                     outFile.parentFile?.mkdirs()
                     upscaled.compressToPNG(outFile)
                     upscaled.recycle()
                 }
             }
        }
        zipFile.close()
        
        if (isStopped) return

        statusProvider.updateProgress(UpscaleProgress(mangaId, chapterId, total, total, 1, 1, factor, 0, UpscaleProgress.Status.SAVING))

        // Re-zip
        val tempZip = File(applicationContext.cacheDir, "upscaled_${file.name}")
        val zos = ZipOutputStream(FileOutputStream(tempZip))
        tempDir.walkTopDown().filter { it.isFile }.forEach { f ->
            val entryName = f.relativeTo(tempDir).path
            zos.putNextEntry(ZipEntry(entryName))
            f.inputStream().use { it.copyTo(zos) }
            zos.closeEntry()
        }
        zos.close()
        
        // Replace
        file.delete()
        tempZip.renameTo(file)
        tempDir.deleteRecursively()
        
        // Create marker
        File(file.parentFile, "${file.name}.upscaled").createNewFile()
        
        // Notify UI to refresh upscaled status
        try {
            localStorageChanges.emit(LocalMangaParser(file.parentFile.toUri()).getManga(withDetails = false))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    private suspend fun upscaleDirectory(uri: Uri, factor: Int, builder: NotificationCompat.Builder, notificationId: Int, mangaId: Long, chapterId: Long) {
        val dir = uri.toFile()
        val files = dir.listFiles { f -> isImage(f.name) } ?: return
        val total = files.size
        
        builder.setProgress(total, 0, false)
        notificationManager.notify(notificationId, builder.build())
        
        val startTime = System.currentTimeMillis()
        files.forEachIndexed { index, file ->
            if (isStopped) return@forEachIndexed
            
            builder.setProgress(total, index + 1, false)
            notificationManager.notify(notificationId, builder.build())
            
            val bitmap = try { BitmapDecoderCompat.decode(file) } catch (e: Exception) { null }
            if (bitmap != null) {
                val upscaled = upscaler.upscale(bitmap, factor) { parts: Int, pTotal: Int ->
                    val elapsed = System.currentTimeMillis() - startTime
                    val avgPerPage = if (index > 0) elapsed / index else (elapsed / (parts.toFloat() / pTotal)).toLong()
                    val timeLeft = (total - index) * (avgPerPage / 1000)
                    
                    runBlocking {
                        statusProvider.updateProgress(UpscaleProgress(
                            mangaId, chapterId, total, index, parts, pTotal, factor, timeLeft, UpscaleProgress.Status.PROCESSING
                        ))
                    }
                }
                bitmap.recycle()
                
                if (upscaled != null) {
                    upscaled.compressToPNG(file)
                    upscaled.recycle()
                }
            }
        }
        
        // Create marker
        val marker = File(dir, ".upscaled")
        marker.createNewFile()
        
        // Notify UI to refresh upscaled status
        try {
            localStorageChanges.emit(LocalMangaParser(dir.parentFile.toUri()).getManga(withDetails = false))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun isImage(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".jpg") || n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".jpeg")
    }
}
