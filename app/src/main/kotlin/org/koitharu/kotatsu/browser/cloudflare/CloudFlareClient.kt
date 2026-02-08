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
				const MIN_DELAY = 1000;
				const MAX_DELAY = 3000;
				const CHECK_INTERVAL = 2000;
				
				function getRandomDelay() {
					return Math.floor(Math.random() * (MAX_DELAY - MIN_DELAY + 1)) + MIN_DELAY;
				}

				function findWidget(root) {
					// Direct check in current root
					const widget = root.querySelector('#challenge-stage input[type="checkbox"]') ||
						   root.querySelector('input[name="cf-turnstile-response"]') ||
						   root.querySelector('.ctp-checkbox-container input') ||
						   root.querySelector('.cf-turnstile-wrapper iframe') ||
						   root.querySelector('#turnstile-wrapper iframe');
					
					if (widget) return widget;

					// Deep check in Shadow DOMs
					const all = root.querySelectorAll('*');
					for (let i = 0; i < all.length; i++) {
						if (all[i].shadowRoot) {
							const found = findWidget(all[i].shadowRoot);
							if (found) return found;
						}
					}
					return null;
				}

				function attemptClick() {
					const element = findWidget(document);
					if (element) {
						setTimeout(() => {
							if (element.tagName === 'IFRAME') {
								element.focus();
							} else {
								element.focus();
								element.click();
								element.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
								element.dispatchEvent(new Event('change', { bubbles: true }));
								element.dispatchEvent(new Event('input', { bubbles: true }));
							}
						}, getRandomDelay());
						return true;
					}
					return false;
				}

				const observer = new MutationObserver((mutations) => {
					if (attemptClick()) {
						observer.disconnect();
					}
				});
				
				observer.observe(document.body, { childList: true, subtree: true });
				
				if (attemptClick()) {
					observer.disconnect();
				}
				
				const interval = setInterval(() => {
					if (attemptClick()) {
						clearInterval(interval);
						observer.disconnect();
					}
				}, CHECK_INTERVAL);
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
