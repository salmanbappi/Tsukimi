package org.koitharu.kotatsu.core.network.webview

import android.graphics.Bitmap
import android.webkit.WebView
import org.koitharu.kotatsu.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import kotlin.coroutines.Continuation

class CaptchaContinuationClient(
	private val cookieJar: MutableCookieJar,
	private val targetUrl: String,
	continuation: Continuation<Unit>,
) : ContinuationResumeWebViewClient(continuation) {

	private val oldClearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)

	override fun onPageFinished(view: WebView?, url: String?) {
		super.onPageFinished(view, url)
		view?.evaluateJavascript(
			"""
			(function() {
				const MIN_DELAY = 1000;
				const MAX_DELAY = 3000;
				const CHECK_INTERVAL = 2000;
				
				function getRandomDelay() {
					return Math.floor(Math.random() * (MAX_DELAY - MIN_DELAY + 1)) + MIN_DELAY;
				}

				function findWidget(root) {
					const widget = root.querySelector('#challenge-stage input[type="checkbox"]') ||
						   root.querySelector('input[name="cf-turnstile-response"]') ||
						   root.querySelector('.ctp-checkbox-container input') ||
						   root.querySelector('.cf-turnstile-wrapper iframe') ||
						   root.querySelector('#turnstile-wrapper iframe');
					
					if (widget) return widget;

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

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		checkClearance(view)
	}

	private fun checkClearance(view: WebView?) {
		val clearance = CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)
		if (clearance != null && clearance != oldClearance) {
			resumeContinuation(view)
		}
	}
}
