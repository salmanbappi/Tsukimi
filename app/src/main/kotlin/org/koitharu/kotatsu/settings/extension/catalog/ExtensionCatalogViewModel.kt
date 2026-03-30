package org.koitharu.kotatsu.settings.extension.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.extension.data.ExtensionRepoRepository
import org.koitharu.kotatsu.extension.model.ExtensionJsonObject
import javax.inject.Inject

@HiltViewModel
class ExtensionCatalogViewModel @Inject constructor(
	private val repository: ExtensionRepoRepository,
	@BaseHttpClient private val httpClient: OkHttpClient,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }
	
	private val _extensions = MutableStateFlow<List<ExtensionJsonObject>>(emptyList())
	val extensions = _extensions.asStateFlow()

	init {
		fetchExtensions()
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
						allExtensions.addAll(list)
					}
				} catch (e: Exception) {
					// Ignore
				}
			}
			
			_extensions.value = allExtensions.sortedBy { it.name }
		}
	}
}