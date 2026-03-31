package org.koitharu.kotatsu.extension.mihon

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.koitharu.kotatsu.core.network.BaseHttpClient
import org.koitharu.kotatsu.core.util.ext.processLifecycleScope
import org.koitharu.kotatsu.extension.mihon.model.MihonMangaSource
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MihonExtensionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @BaseHttpClient private val httpClient: OkHttpClient,
) {
    private val loader = MihonExtensionLoader(context)
    private val _installedExtensions = MutableStateFlow<Map<String, List<MihonMangaSource>>>(emptyMap())
    val installedExtensions: StateFlow<Map<String, List<MihonMangaSource>>> = _installedExtensions

    init {
        // Core Injekt setup should have happened in BaseApp.onCreate
        // Here we just ensure this manager is also available via Injekt if needed
        KotoInjektBridge.setup(context, httpClient)
        KotoInjektBridge.registerMihonManager(this)
        refreshInstalledExtensions()
    }

    fun refreshInstalledExtensions() {
        processLifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val packages = pm.getInstalledPackages(0)
                    .filter { it.packageName.startsWith("eu.kanade.tachiyomi.extension.") }
                
                val newExtensions = packages.associate { pkg ->
                    pkg.packageName to loader.loadExtension(pkg.packageName)
                }
                _installedExtensions.value = newExtensions
            } catch (e: Throwable) {
                // Ignore
            }
        }
    }

    fun getSource(id: String): MihonMangaSource? {
        return _installedExtensions.value.values.flatten().find { it.name == id }
    }
}
