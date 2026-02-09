package org.koitharu.kotatsu.reader.domain

import android.graphics.Bitmap

/**
 * Interface for the AI Upscaling functionality.
 * This runs locally within the application using native NCNN libraries.
 */
interface UpscaleManager {

    /**
     * Checks if the required native libraries and models are loaded.
     */
    fun isReady(): Boolean

    /**
     * Upscales the given bitmap using the selected model.
     * This method is blocking and must be called on a background thread.
     *
     * @param input The source bitmap (e.g., a manga page).
     * @param model The model identifier (e.g., "realesrgan-x4plus-anime").
     * @param param The parameters for the model (e.g., tile size, scaling factor).
     * @return The upscaled bitmap, or null if processing failed.
     */
    suspend fun upscale(input: Bitmap, model: String, param: UpscaleParams): Bitmap?

    data class UpscaleParams(
        val scale: Int = 2,
        val tileSize: Int = 512, // Larger tiles = fewer passes, slightly more RAM
        val denoise: Int = -1 // -1: None, 0: Low, 3: High
    )
}
