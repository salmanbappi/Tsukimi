# AI Upscale Implementation Plan

Upgrade the reading experience by automatically upscaling manga pages to 4K+ resolution using on-device AI (TensorFlow Lite).

## Objectives
1.  **Automatic High-Res:** upscale pages automatically when the feature is enabled.
2.  **Zero-Lag Reading:** Use background pre-processing and "hot-swapping" to ensure the user never waits for an upscale.
3.  **Elite Quality:** Support state-of-the-art models like Real-ESRGAN and Real-CUGAN.
4.  **Smart Memory:** Use image tiling to process large manga pages without Out-of-Memory (OOM) crashes.

## Technical Architecture

### 1. The Engine (`UpscaleManager.kt`)
- **Framework:** TensorFlow Lite with GPU Acceleration (OpenCL/NNAPI).
- **Strategy:** **Image Tiling**.
    - Divide a 2000px page into 128x128 or 256x256 tiles.
    - Process each tile through the AI model.
    - Stitch tiles back together with a 16px overlap to prevent seams.
- **Model:** Real-ESRGAN (4x) or Waifu2x (2x/4x) quantized for mobile.

### 2. The Pipeline (Integration with `PageLoader`)
- **Background Worker:** A background task will follow the `PageLoader` prefetcher.
- **Cache Level:** Introduce an `upscale_cache` directory.
- **Flow:**
    1.  **Prefetch:** When Page 5 is downloaded, the background worker immediately starts upscaling it.
    2.  **Instant Load:** When the user scrolls to Page 5, the app first loads the original image (instant).
    3.  **Hot-Swap:** If the upscaled version is ready (or becomes ready), the app seamlessly swaps the image in the viewer.

### 3. Smoothness Guarantee
- **GPU Delegation:** Offload compute to the phone's GPU to keep the CPU free for UI and scrolling.
- **Tile-based Processing:** Prevents huge memory spikes.
- **Priority Queue:** Upscale the *current* page with high priority, and *next* pages with low priority.

## Implementation Steps

### Phase 1: Foundation (The "Brain")
- [ ] Add `tensorflow-lite-support` and `tensorflow-lite-gpu` dependencies.
- [ ] Implement `UpscaleManager` with TFLite initialization and GPU support.
- [ ] Develop the `TilingEngine` for processing large Bitmaps in chunks.

### Phase 2: Integration (The "Nerves")
- [ ] Hook `UpscaleManager` into `PageLoader`.
- [ ] Implement the `upscale_cache` logic to persist high-res images.
- [ ] Update `BasePageHolder` to support "hot-swapping" from original to high-res.

### Phase 3: UI & Polish (The "Face")
- [ ] Add "AI Upscaling" toggle in Reader Settings.
- [ ] Add Model Selection (Fast / Balanced / High Quality).
- [ ] Add a subtle "AI" indicator/badge on upscaled pages.

## Research Notes
- **Real-ESRGAN** is the target for high-quality line art.
- **Vulkan** (via ncnn) is an alternative if TFLite GPU performance is insufficient, but TFLite is more idiomatic for this project's stack.
- **Overlap & Blending:** Use a small overlap (e.g., 10%) when tiling to avoid visible grid lines.
