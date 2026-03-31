package org.koitharu.kotatsu.extension.mihon

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingletonFactory
import uy.kohesive.injekt.api.get

object KotoInjektBridge {
    fun setup(context: Context, httpClient: OkHttpClient) {
        try {
            Injekt.get<Context>()
        } catch (e: Exception) {
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingletonFactory { context }
                    addSingletonFactory { context.applicationContext as Application }
                    addSingletonFactory { httpClient }
                    val networkHelper = MihonNetworkHelper(httpClient)
                    addSingletonFactory<NetworkHelper> { networkHelper }
                }
            })
        }
    }

    fun registerMihonManager(manager: MihonExtensionManager) {
        try {
            Injekt.get<MihonExtensionManager>()
        } catch (e: Exception) {
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    addSingletonFactory { manager }
                }
            })
        }
    }
}
