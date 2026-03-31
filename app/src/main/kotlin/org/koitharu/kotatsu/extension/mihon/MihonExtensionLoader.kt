package org.koitharu.kotatsu.extension.mihon

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import dalvik.system.PathClassLoader
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceFactory
import org.koitharu.kotatsu.extension.mihon.model.MihonMangaSource

class MihonExtensionLoader(private val context: Context) {

    fun loadExtension(packageName: String): List<MihonMangaSource> {
        val packageManager = context.packageManager
        val packageInfo = try {
            packageManager.getPackageInfo(packageName, PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            Log.e("MihonExtensionLoader", "Failed to get package info for $packageName", e)
            return emptyList()
        }

        val appInfo = packageInfo.applicationInfo ?: return emptyList()
        val classLoader = PathClassLoader(appInfo.sourceDir, null, context.classLoader)
        
        val extensionClass = try {
            val metadata = appInfo.metaData
            val className = metadata?.getString("pane.koitharu.kotatsu.extension.class")
                ?: metadata?.getString("tachiyomi.extension.class")
                ?: run {
                    Log.w("MihonExtensionLoader", "No extension class metadata found for $packageName")
                    return emptyList()
                }
            Log.d("MihonExtensionLoader", "Loading extension class $className for $packageName")
            classLoader.loadClass(className)
        } catch (e: Throwable) {
            Log.e("MihonExtensionLoader", "Failed to load extension class for $packageName", e)
            return emptyList()
        }

        val sources = mutableListOf<Source>()
        try {
            val obj = extensionClass.getDeclaredConstructor().newInstance()
            Log.d("MihonExtensionLoader", "Instantiated extension class ${extensionClass.name}")
            if (obj is SourceFactory) {
                sources.addAll(obj.createSources())
            } else if (obj is Source) {
                sources.add(obj)
            }
        } catch (e: Throwable) {
            Log.e("MihonExtensionLoader", "Failed to instantiate extension $packageName", e)
            return emptyList()
        }

        return sources.filterIsInstance<CatalogueSource>().map { 
            MihonMangaSource(it, packageName) 
        }
    }
}
