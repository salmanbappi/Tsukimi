package org.koitharu.kotatsu.settings.extension.repos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.extension.data.ExtensionRepoRepository
import org.koitharu.kotatsu.extension.model.ExtensionRepoMetaDto
import org.koitharu.kotatsu.extension.model.toExtensionRepo
import javax.inject.Inject

@HiltViewModel
class ExtensionReposViewModel @Inject constructor(
	private val repository: ExtensionRepoRepository,
	@BaseHttpClient private val httpClient: OkHttpClient,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true }

	val repos = repository.observeAll().stateIn(
		scope = viewModelScope,
		started = SharingStarted.Lazily,
		initialValue = emptyList(),
	)

	fun addRepo(url: String, onResult: (Boolean, String?) -> Unit) {
		viewModelScope.launch(Dispatchers.IO) {
			try {
                // E.g. https://raw.githubusercontent.com/mihonapp/mihon/main/index.min.json
				val formattedUrl = url.trim()
                if (!formattedUrl.endsWith("/index.min.json")) {
                    withContext(Dispatchers.Main) { onResult(false, "Invalid URL. Must end with /index.min.json") }
                    return@launch
                }
                
                val baseUrl = formattedUrl.removeSuffix("/index.min.json")
				val request = Request.Builder()
					.url("$baseUrl/repo.json")
					.build()
				
				val response = httpClient.newCall(request).execute()
				if (!response.isSuccessful) {
				    withContext(Dispatchers.Main) { onResult(false, "HTTP ${response.code}") }
				    return@launch
				}
				
				val body = response.body?.string() ?: throw Exception("Empty body")
				val metaDto = json.decodeFromString<ExtensionRepoMetaDto>(body)
				repository.add(metaDto.toExtensionRepo(baseUrl))
				withContext(Dispatchers.Main) { onResult(true, null) }
			} catch (e: Exception) {
				withContext(Dispatchers.Main) { onResult(false, e.message) }
			}
		}
	}

	fun deleteRepo(baseUrl: String) {
		viewModelScope.launch(Dispatchers.IO) {
			repository.delete(baseUrl)
		}
	}
}