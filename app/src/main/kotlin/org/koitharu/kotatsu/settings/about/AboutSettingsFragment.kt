package org.koitharu.kotatsu.settings.about

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.annotation.StringRes
import androidx.core.app.ShareCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.github.AppVersion
import org.koitharu.kotatsu.core.github.VersionId
import org.koitharu.kotatsu.core.github.isStable
import org.koitharu.kotatsu.core.nav.router
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.ui.BasePreferenceFragment
import org.koitharu.kotatsu.core.util.ext.observe
import org.koitharu.kotatsu.core.util.ext.observeEvent
import java.io.BufferedReader
import java.io.InputStreamReader

@AndroidEntryPoint
class AboutSettingsFragment : BasePreferenceFragment(R.string.about) {

	private val viewModel by viewModels<AboutSettingsViewModel>()

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		addPreferencesFromResource(R.xml.pref_about)
		findPreference<Preference>(AppSettings.KEY_APP_VERSION)?.run {
			title = getString(R.string.app_version, BuildConfig.VERSION_NAME)
		}
		findPreference<SwitchPreferenceCompat>(AppSettings.KEY_UPDATES_UNSTABLE)?.run {
			isEnabled = VersionId(BuildConfig.VERSION_NAME).isStable
			if (!isEnabled) isChecked = true
		}
	}

	override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
		super.onViewCreated(view, savedInstanceState)
		combine(viewModel.isUpdateSupported, viewModel.isLoading, ::Pair)
			.observe(viewLifecycleOwner) { (isUpdateSupported, isLoading) ->
				findPreference<Preference>(AppSettings.KEY_UPDATES_UNSTABLE)?.isVisible = isUpdateSupported
				findPreference<Preference>(AppSettings.KEY_APP_VERSION)?.isEnabled = isUpdateSupported && !isLoading

			}
		viewModel.onUpdateAvailable.observeEvent(viewLifecycleOwner, ::onUpdateAvailable)
	}

	override fun onPreferenceTreeClick(preference: Preference): Boolean {
		return when (preference.key) {
			AppSettings.KEY_APP_VERSION -> {
				viewModel.checkForUpdates()
				true
			}

			AppSettings.KEY_DUMP_CRASH_LOG -> {
				dumpCrashLog()
				true
			}

			AppSettings.KEY_LINK_WEBLATE -> {
				openLink(R.string.url_weblate, preference.title)
				true
			}

			AppSettings.KEY_LINK_GITHUB -> {
				openLink(R.string.url_github, preference.title)
				true
			}

			AppSettings.KEY_LINK_MANUAL -> {
				openLink(R.string.url_user_manual, preference.title)
				true
			}

			AppSettings.KEY_LINK_TELEGRAM -> {
				if (!openLink(R.string.url_telegram, null)) {
					openLink(R.string.url_telegram_web, preference.title)
				}
				true
			}

			else -> super.onPreferenceTreeClick(preference)
		}
	}

	private fun onUpdateAvailable(version: AppVersion?) {
		if (version == null) {
			Snackbar.make(listView, R.string.no_update_available, Snackbar.LENGTH_SHORT).show()
		} else {
			startActivity(Intent(requireContext(), AppUpdateActivity::class.java))
		}
	}

	private fun openLink(
		@StringRes url: Int,
		title: CharSequence?
	): Boolean = if (router.openExternalBrowser(getString(url), title)) {
		true
	} else {
		Snackbar.make(listView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		false
	}

	private fun dumpCrashLog() {
		lifecycleScope.launch {
			val log = withContext(Dispatchers.IO) {
				try {
					val sb = StringBuilder()
					sb.append("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
					sb.append("Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n")
					sb.append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT})\n")
					sb.append("\n--- Logcat ---\n")
					
					val process = Runtime.getRuntime().exec("logcat -d MihonRestore:D BackupRepository:D *:E")
					val reader = BufferedReader(InputStreamReader(process.inputStream))
					var line: String?
					while (reader.readLine().also { line = it } != null) {
						sb.append(line).append("\n")
					}
					sb.toString()
				} catch (e: Exception) {
					"Failed to dump log: ${e.message}"
				}
			}
			
			ShareCompat.IntentBuilder(requireContext())
				.setType("text/plain")
				.setSubject("Kotatsu Crash Log")
				.setText(log)
				.setChooserTitle("Share Crash Log")
				.startChooser()
		}
	}
}
