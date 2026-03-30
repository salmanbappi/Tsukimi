package org.koitharu.kotatsu.extension.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import org.koitharu.kotatsu.extension.model.ExtensionJsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionInstaller @Inject constructor(
	@ApplicationContext private val context: Context,
) {
	private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

	fun install(extension: ExtensionJsonObject) {
		val url = "${extension.repoUrl}/apk/${extension.apk}"
		val request = DownloadManager.Request(Uri.parse(url))
			.setTitle(extension.name)
			.setDescription("Downloading extension...")
			.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
			.setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, extension.apk)
			.setAllowedOverMetered(true)
			.setAllowedOverRoaming(true)
			.setMimeType("application/vnd.android.package-archive")

		val downloadId = downloadManager.enqueue(request)
		
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
				if (id == downloadId) {
					installApk(extension.apk)
					context.unregisterReceiver(this)
				}
			}
		}
		context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED)
	}

	private fun installApk(apkName: String) {
		val file = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), apkName)
		val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
		
		val intent = Intent(Intent.ACTION_VIEW).apply {
			setDataAndType(uri, "application/vnd.android.package-archive")
			addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
			addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
		}
		context.startActivity(intent)
	}
}