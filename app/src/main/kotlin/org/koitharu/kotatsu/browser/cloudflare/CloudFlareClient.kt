package org.koitharu.kotatsu.browser.cloudflare

import android.graphics.Bitmap
import android.webkit.WebView
import org.koitharu.kotatsu.browser.BrowserClient
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.core.network.webview.adblock.AdBlock
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

private const val LOOP_COUNTER = 3

class CloudFlareClient(
	private val cookieJar: MutableCookieJar,
	private val callback: CloudFlareCallback,
	adBlock: AdBlock,
	private val targetUrl: String,
) : BrowserClient(callback, adBlock) {

	private val oldClearance = getClearance()
	private var counter = 0

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		checkClearance()
	}

	override fun onPageCommitVisible(view: WebView, url: String) {
		super.onPageCommitVisible(view, url)
		callback.onPageLoaded()
	}

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		callback.onPageLoaded()
		webView.evaluateJavascript(
			"""
			(function() {
				function click() {
					const checkbox = document.querySelector('#challenge-stage input[type="checkbox"]') ||
									 document.querySelector('input[name="cf-turnstile-response"]');
					if (checkbox) {
						checkbox.click();
					} else {
						// Look in shadow roots
						document.querySelectorAll('*').forEach(el => {
							if (el.shadowRoot) {
								const cb = el.shadowRoot.querySelector('input[type="checkbox"]');
								if (cb) cb.click();
							}
						});
					}
				}
				setInterval(click, 1000);
			})();
			""".trimIndent(),
			null
		)
	}

	fun reset() {
		counter = 0
	}

	private fun checkClearance() {
		val clearance = getClearance()
		if (clearance != null && clearance != oldClearance) {
			callback.onCheckPassed()
		} else {
			counter++
			if (counter >= LOOP_COUNTER) {
				reset()
				callback.onLoopDetected()
			}
		}
	}

	private fun getClearance() = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
}
