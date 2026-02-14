# Project Tsukimi: Grand Overhaul (The "Premium" Standard)

**Objective:** Transform the application from a functional manga reader into a "Class-A" premium experience. We are moving beyond bug fixes to **feature evolution**.

## Core Pillars
1.  **Immersion (UI/UX):** The app should feel alive. Every interaction must have feedback. The interface should recede when reading.
2.  **Intelligence (AI):** AI features (Upscale, Translation) should happen *magically* and unobtrusively, not just as toggles.
3.  **Performance (Engine):** Zero lag. Instant loading. Predictive behavior.

---

## Phase 1: The "Living" Interface (UI/UX Revolution)
**Goal:** Complete Material 3 adoption with fluid motion.

*   [ ] **Dynamic Color Engine:** Fully implement Material You (Monet) dynamic theming that pulls colors from the current manga cover art, not just the system wallpaper.
*   [ ] **Micro-Interactions:**
    *   Add "spring" animations to list scrolling (overscroll).
    *   Add shared-element transitions: When clicking a manga cover, it should *morph* into the details page header, not just pop up.
    *   Add "breathing" skeleton loaders instead of spinning circles.
*   [ ] **Reader Immersion:**
    *   **Immersive Mode 2.0:** Status bars and navigation should fade out based on scroll velocity, not just tap.
    *   **Page Turn Effects:** Implement a realistic "Page Curl" animation (using a GL shader) as an option alongside standard slide/webtoon scroll.

## Phase 2: AI Intelligence 2.0 (Smart Features)
**Goal:** Make AI useful, not just a gimmick.

*   [ ] **Smart AI Upscaling (The "Magic Zoom"):**
    *   *Current:* Global toggle (slow, drains battery).
    *   *New:* **On-Demand Detail.** Upscaling is triggered only when the user *zooms in* on a page, or proactively on the *next* page in the background while reading.
*   [ ] **Seamless Translation (In-Painting Lite):**
    *   *Current:* Boxy overlays that obscure art.
    *   *New:* **Context-Aware Bubbles.** Use ML Kit's face/object detection to ensure text bubbles rarely cover faces.
    *   **Style Matching:** Extract the background color of the original bubble (white/black/screentone) and match the translation overlay's background and text color automatically.
    *   **Shape Matching:** Instead of rounded rectangles, generate a `Path` that roughly follows the original bubble's contour.

## Phase 3: Performance & Architecture (The Engine)
**Goal:** "60 FPS or Die."

*   [ ] **Predictive Prefetching:**
    *   Track reading speed. If a user reads 1 page every 10 seconds, start prefetching/decoding the next chapter 30 seconds before they finish the current one.
    *   **RAM Management:** Implement a smarter LRU cache that aggressively clears *previous* chapters to make room for high-res *future* chapters.
*   [ ] **Database Optimization:**
    *   Switch main lists to use `Paging 3` (if not already) for infinite scalability.
    *   Optimize SQL queries for the "Library" view to handle 5,000+ entries without dropping frames.

## Phase 4: Code & Quality (The Foundation)
*   [ ] **Modularization:** Extract `feature:reader`, `feature:library`, and `core:ai` into separate Gradle modules to enforce separation of concerns and speed up build times.
*   [ ] **Automated Benchmark:** Add a macrobenchmark test to measure startup time and frame timing on every commit.

---

## Execution Strategy
We will tackle **Phase 1 (UI)** and **Phase 2 (AI)** concurrently.

**Immediate Next Step:** Implement **Shared Element Transitions** for the library -> details flow. This is the single biggest "premium feel" upgrade for a media app.
