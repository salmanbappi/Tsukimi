# Performance & Stability Audit

## Scrolling Performance
**Issue:** Severe scrolling lag (jank) was caused by a global `convertLock` in `PageLoader.kt`. This lock forced all image operations (conversion, filtering, upscaling) to happen one at a time. If an upscale or filter operation took 200ms, the UI could not load or display *any* other page during that time.
**Fix:** Replaced the global lock with a `ConcurrentHashMap` of per-file locks.
**Result:** Pages can now be processed in parallel. Loading one page no longer blocks the scrolling of others.

## AI Upscale Verification
**Issue:** The feature appeared silent/broken. Logic was sound but blocked by the concurrency issue above.
**Fix:**
1.  Verified C++ implementation (NCNN/Real-ESRGAN) is present and correct.
2.  Verified model assets are in `app/src/main/assets`.
3.  Added logging to `PageViewModel` to confirm execution.
**Verification:** Run `adb logcat -s PageViewModel` to see "Starting AI Upscale..." and "AI Upscale success..." logs.

## AI Translation Overlay
**Issue:** Bubbles felt "detached" because the overlay was updating on an animation loop rather than synchronizing perfectly with the image view's scale/pan events.
**Fix:**
1.  Implemented `OnStateChangedListener` on the `SubsamplingScaleImageView`.
2.  Removed the asynchronous `postInvalidateOnAnimation` loop.
**Result:** The overlay now repaints strictly when the image moves, eliminating the "drift" or "floaty" feeling.
