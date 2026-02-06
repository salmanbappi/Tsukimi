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
				const MAX_DELAY = 2500;
				
				function getRandomDelay() {
					return Math.floor(Math.random() * (MAX_DELAY - MIN_DELAY + 1)) + MIN_DELAY;
				}

				function findCheckbox(root) {
					return root.querySelector('#challenge-stage input[type="checkbox"]') ||
						   root.querySelector('input[name="cf-turnstile-response"]') ||
						   root.querySelector('.ctp-checkbox-container input') ||
						   root.querySelector('.cf-turnstile-wrapper iframe') ||
						   root.querySelector('#turnstile-wrapper iframe');
				}

				function attemptClick() {
					const element = findCheckbox(document);
					if (element) {
						if (element.tagName === 'IFRAME') {
							// If it's an iframe, we can't click inside easily due to cross-origin
							// But we can try to focus it or wait for automatic resolution
							element.focus();
							return true;
						}
						setTimeout(() => {
							element.click();
							// Also try to dispatch events for better simulation
							element.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
							element.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
							element.dispatchEvent(new MouseEvent('click', { bubbles: true }));
						}, getRandomDelay());
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
						// Don't disconnect immediately, might need multiple attempts
					}
				});
				
				observer.observe(document.body, { childList: true, subtree: true });
				
				// Initial attempt
				attemptClick();
				
				// Failsafe interval
				const interval = setInterval(() => {
					if (attemptClick()) {
						// Keep observing
					}
				}, 3000);
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
