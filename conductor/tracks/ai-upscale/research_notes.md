# AI Upscaling Research: Android & Anime Focus

## 1. Engine Comparison: TFLite vs. NCNN

### TensorFlow Lite (TFLite)
*   **Pros:**
    *   Already in `build.gradle`.
    *   Idiomatic Android (Java/Kotlin API).
    *   Good for standard classification/detection (like the existing ML Kit OCR).
*   **Cons:**
    *   **Model Availability:** High-quality GANs (Real-ESRGAN) are rarely distributed as `.tflite`.
    *   **Conversion Hell:** Converting PyTorch -> ONNX -> TF -> TFLite is prone to operator incompatibility (especially for dynamic tile sizes or custom layers in GANs).
    *   **Performance:** GPU delegates can be finicky with custom operators.

### NCNN (Vulkan)
*   **Pros:**
    *   **Industry Standard:** The de-facto standard for running Real-ESRGAN and Waifu2x on mobile.
    *   **Model Zoo:** Pre-converted models for `realesrgan-x4plus-anime`, `waifu2x-cunet`, etc., are widely available.
    *   **Performance:** Highly optimized Vulkan compute shaders for Adreno/Mali GPUs.
*   **Cons:**
    *   Requires JNI (C++) glue code (though libraries like `ncnn-android-lib` exist).
    *   Adds binary size.

### Recommendation
For **Kotatsu/Tsukimi**, while TFLite is currently planned, **NCNN is technically superior** for this specific feature. However, if staying pure Kotlin is a hard constraint, we must find a pre-converted TFLite model or use `ONNX Runtime` which has better compatibility than TFLite for these models.

## 2. Model Selection for Line Art

| Model | Style | Artifacts | Speed | Recommendation |
| :--- | :--- | :--- | :--- | :--- |
| **Real-ESRGAN-x4plus-anime** | Sharp, crisp lines | Can hallucinate details; occasional "oil painting" effect | Fast (optimized) | **Best for "HD" look** |
| **Waifu2x-CUNET** | Soft, faithful | Very low; preserves original intent | Slower | **Best for purists** |
| **Real-CUGAN** | Balanced | Good texture preservation | Very Fast | **Strong Contender** |

## 3. Workflow Proposal

### A. Immediate (External CLI)
Use **Termux** + `waifu2x-ncnn-vulkan`.
*   **Why:** Scriptable, batch processing, zero app dev time.
*   **Setup:** Compile ncnn binary in Termux.

### B. Integrated (App Feature)
*   **Strategy:** Implement `UpscaleManager` using **NCNN** via JNI, not TFLite.
*   **Pipeline:**
    1.  `PageLoader` downloads image.
    2.  `UpscaleManager` checks cache.
    3.  If miss: Submit to background NCNN worker.
    4.  Worker tiles image (512x512) -> NCNN -> Stitch.
    5.  Save to disk -> Notify UI.

## 4. Tiling Strategy (Crucial for Android)
*   **Memory:** A 4K upscaled page (approx 3000x4500) uses ~54MB RAM (bitmap). Two buffers = 100MB. Manageable, but processing requires intermediate tensors.
*   **Tiling:** Process 256x256 tiles with 16px padding.
*   **Padding:** Essential to prevent "grid artifacts" at tile borders.

## 5. Denoising
*   **Rule:** For Manga/Line Art, **disable denoising** (Level -1 or 0) unless the source is a raw scan with paper grain. Denoising kills screen tones (screentone dots are often mistaken for noise).
