package org.koitharu.kotatsu.extension.mihon

import eu.kanade.tachiyomi.network.NetworkHelper
import okhttp3.OkHttpClient

class MihonNetworkHelper(
    override val client: OkHttpClient
) : NetworkHelper() {
    override fun defaultUserAgentProvider(): String {
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
