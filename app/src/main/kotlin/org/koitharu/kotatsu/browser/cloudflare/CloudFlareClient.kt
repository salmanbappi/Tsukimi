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
				const MIN_DELAY = 1500;
				const MAX_DELAY = 3500;
				
				function getRandomDelay() {
					return Math.floor(Math.random() * (MAX_DELAY - MIN_DELAY + 1)) + MIN_DELAY;
				}

				function findCheckbox(root) {
					return root.querySelector('#challenge-stage input[type="checkbox"]') ||
						   root.querySelector('input[name="cf-turnstile-response"]') ||
						   root.querySelector('.ctp-checkbox-container input');
				}

				function attemptClick() {
					const checkbox = findCheckbox(document);
					if (checkbox) {
						setTimeout(() => checkbox.click(), getRandomDelay());
						return true;
					}
					
					// Look in shadow roots
					const all = document.querySelectorAll('*');
					for (let i = 0; i < all.length; i++) {
						const el = all[i];
						if (el.shadowRoot) {
							const cb = findCheckbox(el.shadowRoot);
							if (cb) {
								setTimeout(() => cb.click(), getRandomDelay());
								return true;
							}
						}
					}
					return false;
				}

				// MutationObserver to watch for dynamic injection of the challenge
				const observer = new MutationObserver((mutations) => {
					if (attemptClick()) {
						observer.disconnect();
					}
				});
				
				observer.observe(document.body, { childList: true, subtree: true });
				
				// Initial attempt
				if (attemptClick()) {
					observer.disconnect();
				}
				
				// Failsafe interval
				const interval = setInterval(() => {
					if (attemptClick()) {
						clearInterval(interval);
						observer.disconnect();
					}
				}, 4000);
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
