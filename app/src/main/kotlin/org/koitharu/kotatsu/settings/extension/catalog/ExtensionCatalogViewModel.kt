package org.koitharu.kotatsu.settings.extension.catalog

import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.parser.external.ExternalMangaSource
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.explore.data.MangaSourcesRepository
import org.koitharu.kotatsu.extension.data.ExtensionRepoRepository
import org.koitharu.kotatsu.extension.model.ExtensionJsonObject
import org.koitharu.kotatsu.extension.model.toExtensionRepo
import org.koitharu.kotatsu.extension.util.ExtensionInstaller
import javax.inject.Inject

@HiltViewModel
class ExtensionCatalogViewModel @Inject constructor(
	@ApplicationContext private val context: Context,
	private val repository: ExtensionRepoRepository,
	private val sourcesRepository: MangaSourcesRepository,
	@BaseHttpClient private val httpClient: OkHttpClient,
	private val installer: ExtensionInstaller,
) : ViewModel() {

	private val json = Json { ignoreUnknownKeys = true }

	private val _allExtensions = MutableStateFlow<List<ExtensionJsonObject>>(emptyList())
	private val _query = MutableStateFlow("")
	private val _selectedLang = MutableStateFlow<String?>(null)
	private val _showNsfw = MutableStateFlow(true)

	val onActionDone = MutableEventFlow<Int>()

	val extensions = combine(_allExtensions, _query, _selectedLang, _showNsfw) { all, query, lang, nsfw ->
		val installedPackages = getInstalledExtensionPackages()

		val filtered = all.filter { ext ->
			(query.isEmpty() || ext.name.contains(query, ignoreCase = true)) &&
				(lang == null || ext.lang == lang) &&
				(nsfw || ext.nsfw == 0)
		}.map { ext ->
			ext.copy(isInstalled = ext.pkg in installedPackages)
		}

		val installed = filtered.filter { it.isInstalled }.sortedBy { it.name }
		val available = filtered.filter { !it.isInstalled }

		mutableListOf<CatalogItem>().apply {
			if (installed.isNotEmpty()) {
				add(CatalogItem.Header("Installed"))
				addAll(installed.map { CatalogItem.Extension(it) })
			}

			if (available.isNotEmpty()) {
				if (lang != null) {
					add(CatalogItem.Header(lang.uppercase()))
					addAll(available.sortedBy { it.name }.map { CatalogItem.Extension(it) })
				} else {
					val nsfwList = available.filter { it.nsfw == 1 }.sortedBy { it.name }
					val clean = available.filter { it.nsfw == 0 }

					if (clean.isNotEmpty()) {
						add(CatalogItem.Header("All"))
						addAll(clean.sortedBy { it.name }.map { CatalogItem.Extension(it) })
					}

					if (nsfwList.isNotEmpty()) {
						add(CatalogItem.Header("18+ / Hentai"))
						addAll(nsfwList.map { CatalogItem.Extension(it) })
					}
				}
			}
		}
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.Lazily,
		initialValue = emptyList(),
	)

	val languages = _allExtensions.map { list: List<ExtensionJsonObject> ->
		list.map { it.lang }.distinct().sorted()
	}.stateIn(
		scope = viewModelScope,
		started = SharingStarted.Eagerly,
		initialValue = emptyList(),
	)

	init {
		fetchExtensions()
	}

	fun setQuery(query: String) {
		_query.value = query
	}

	fun setLanguage(lang: String?) {
		_selectedLang.value = lang
	}

	fun setNsfwEnabled(enabled: Boolean) {
		_showNsfw.value = enabled
	}

	fun installExtension(extension: ExtensionJsonObject) {
		installer.install(extension)
	}

	fun toggleExtensionSource(extension: ExtensionJsonObject) {
		viewModelScope.launch(Dispatchers.Default) {
			val pm = context.packageManager
			
			// 1. Try Kotatsu-style discovery (Providers)
			var providers = pm.queryIntentContentProviders(
				android.content.Intent("app.kotatsu.parser.PROVIDE_MANGA"), 0,
			).filter { it.providerInfo.packageName == extension.pkg }
			
			// 2. Fallback: Check if the package is installed but doesn't have the specific intent
			// (Standard Tachiyomi/Mihon extensions)
			val sources = if (providers.isNotEmpty()) {
				providers.map { resolveInfo ->
					ExternalMangaSource(
						packageName = resolveInfo.providerInfo.packageName,
						authority = resolveInfo.providerInfo.authority,
					)
				}
			} else {
				// If it's a standard Tachiyomi extension, we try to guess the authority
				// Most use the package name as the authority
				val info = try { pm.getProviderInfo(android.content.ComponentName(extension.pkg, extension.pkg), 0) } catch (e: Exception) { null }
				if (info != null) {
					listOf(ExternalMangaSource(extension.pkg, info.authority))
				} else {
					// Last resort: search for ANY provider in this package
					val pkgInfo = try { pm.getPackageInfo(extension.pkg, android.content.pm.PackageManager.GET_PROVIDERS) } catch (e: Exception) { null }
					pkgInfo?.providers?.map { ExternalMangaSource(extension.pkg, it.authority) } ?: emptyList()
				}
			}
			
			if (sources.isNotEmpty()) {
				sourcesRepository.setSourcesEnabled(sources, true)
				onActionDone.call(R.string.source_enabled)
			} else {
				// Truly not found or not an extension
				fetchExtensions()
			}
		}
	}

	private fun getInstalledExtensionPackages(): Set<String> {
		val pm = context.packageManager
		val packages = pm.getInstalledPackages(0)
		return packages
			.map { it.packageName }
			.filter { it.startsWith("eu.kanade.tachiyomi.extension.") || it.startsWith("org.koitharu.kotatsu.extension.") }
			.toSet()
	}

	private fun fetchExtensions() {
		viewModelScope.launch(Dispatchers.IO) {
			val repos = repository.getAll()
			val allExtensions = mutableListOf<ExtensionJsonObject>()

			for (repo in repos) {
				try {
					val request = Request.Builder()
						.url("${repo.baseUrl}/index.min.json")
						.build()

					val response = httpClient.newCall(request).execute()
					if (response.isSuccessful) {
						val body = response.body?.string() ?: continue
						val list = json.decodeFromString<List<ExtensionJsonObject>>(body)
						allExtensions.addAll(list.map { it.copy(repoUrl = repo.baseUrl) })
					}
				} catch (e: Exception) {
					// Ignore
				}
			}

			_allExtensions.value = allExtensions.sortedBy { it.name }
		}
	}
}
