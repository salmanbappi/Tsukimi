package org.koitharu.kotatsu.extension.mihon

import android.content.Context
import android.util.Log
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
        Log.d("MihonExtensionManager", "Initializing MihonExtensionManager")
        // Core Injekt setup should have happened in BaseApp.onCreate
        // But we call it here too as a safety measure in case this is created earlier (e.g. by Hilt eager injection)
        KotoInjektBridge.setup(context, httpClient)
        KotoInjektBridge.registerMihonManager(this)
        refreshInstalledExtensions()
    }

    fun refreshInstalledExtensions() {
        processLifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("MihonExtensionManager", "Refreshing installed extensions")
                val pm = context.packageManager
                val packages = pm.getInstalledPackages(0)
                    .filter { it.packageName.startsWith("eu.kanade.tachiyomi.extension.") }
                
                Log.d("MihonExtensionManager", "Found ${packages.size} potential extensions")
                
                val newExtensions = packages.associate { pkg ->
                    Log.d("MihonExtensionManager", "Loading extension: ${pkg.packageName}")
                    pkg.packageName to loader.loadExtension(pkg.packageName)
                }
                _installedExtensions.value = newExtensions
                Log.d("MihonExtensionManager", "Extension refresh completed")
            } catch (e: Throwable) {
                Log.e("MihonExtensionManager", "Failed to refresh extensions", e)
            }
        }
    }

    fun getSource(id: String): MihonMangaSource? {
        return _installedExtensions.value.values.flatten().find { it.name == id }
    }
}
