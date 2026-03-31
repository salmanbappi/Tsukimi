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
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    try { addSingletonFactory { context } } catch (e: Throwable) { e.printStackTrace() }
                    try { addSingletonFactory<Application> { context.applicationContext as Application } } catch (e: Throwable) { e.printStackTrace() }
                    try { addSingletonFactory { httpClient } } catch (e: Throwable) { e.printStackTrace() }
                    try { addSingletonFactory<NetworkHelper> { MihonNetworkHelper(httpClient) } } catch (e: Throwable) { e.printStackTrace() }
                }
            })
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun registerMihonManager(manager: MihonExtensionManager) {
        try {
            Injekt.importModule(object : InjektModule {
                override fun InjektRegistrar.registerInjectables() {
                    try { addSingletonFactory { manager } } catch (e: Throwable) { e.printStackTrace() }
                }
            })
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }
}
