# Squad 5: Content Reliability (The "Unbreakable" Pipeline)

**Mission:** Ensure users never see a "Source Error" or "Cloudflare" screen again.

## 1. The "Anti-Cloudflare" Shield (Priority: HIGH)
**Problem:** Many manga sites use Cloudflare Under Attack mode. The app currently fails with `CloudFlareProtectedException`.
**Solution:** Implement an automated `WebView` solver.
*   **Mechanism:** When a 403/503 is detected with Cloudflare markers:
    1.  Pause the request.
    2.  Launch a hidden/minimized WebView to the target URL.
    3.  Wait for the Cloudflare challenge to complete (JS check).
    4.  Extract the `cf_clearance` cookie and User-Agent.
    5.  Inject these into the global `OkHttpClient`.
    6.  Retry the original request.
    *   **Result:** Transparent bypass. The user might see a 2-second spinner, but it *works*.

## 2. Global Source Aggregation (Unified Search)
**Problem:** Users must know which source has the manga they want. If Mangadex is down, they are stuck.
**Solution:** A "Meta-Source" layer.
*   **Feature:** **"Universal Search"**.
*   **Mechanism:**
    *   Query Top 5 stable sources (Mangadex, MangaSee, Manganato, etc.) in parallel.
    *   Deduplicate results by Title + Author.
    *   Show a single manga entry.
    *   When opening a chapter, auto-select the fastest/healthiest source.

## 3. Intelligent Mirror Switching
**Problem:** `MirrorSwitcher` currently relies on hardcoded domain lists or redirects.
**Solution:** Dynamic DNS / DoH fallback.
*   If `mangadex.org` is blocked by ISP, try `1.1.1.1` DoH resolution automatically.
*   Try known mirrors from a remotely updated JSON file (GitHub hosted) instead of hardcoded app updates.

## Execution Plan
We will start with **Feature 1: The Anti-Cloudflare Shield**. This provides the highest immediate value.
