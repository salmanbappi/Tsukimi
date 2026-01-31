package org.koitharu.kotatsu.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

@Singleton
class SuperImageUpscaler @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private var interpreter: Interpreter? = null
    private val mutex = Mutex()
    private val modelFile = File(context.filesDir, "models/realesrgan_x4plus_anime_6b.tflite")

    suspend fun upscale(
        bitmap: Bitmap, 
        factor: Int, 
        onProgress: (parts: Int, total: Int) -> Unit = { _, _ -> }
    ): Bitmap? = mutex.withLock {
        withContext(Dispatchers.Default) {
            if (!modelFile.exists()) return@withContext null

            // Initialize interpreter if needed
            if (interpreter == null) {
                try {
                    val options = Interpreter.Options()
                    if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                        options.addDelegate(GpuDelegate())
                    }
                    interpreter = Interpreter(modelFile, options)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Fallback to CPU if GPU fails
                    val options = Interpreter.Options()
                    interpreter = Interpreter(modelFile, options)
                }
            }

            return@withContext when {
                factor <= 4 -> {
                    val result = runModelSafely(bitmap, onProgress) ?: return@withContext null
                    if (factor == 4) result else resize(result, factor.toDouble() / 4.0)
                }
                factor <= 16 -> {
                    // Pass 1 (4x)
                    val pass1 = runModelSafely(bitmap) { p, t -> onProgress(p, t * 2) } ?: return@withContext null
                    // Pass 2 (Another 4x -> 16x)
                    val pass2 = runModelSafely(pass1) { p, t -> onProgress(t + p, t * 2) }
                    pass1.recycle()
                    if (pass2 == null) return@withContext null
                    if (factor == 16) pass2 else resize(pass2, factor.toDouble() / 16.0)
                }
                else -> null
            }
        }
    }

    private fun resize(bitmap: Bitmap, scale: Double): Bitmap {
        val w = (bitmap.width * scale).toInt()
        val h = (bitmap.height * scale).toInt()
        val result = Bitmap.createScaledBitmap(bitmap, w, h, true)
        bitmap.recycle()
        return result
    }

    private fun runModelSafely(input: Bitmap, onProgress: (parts: Int, total: Int) -> Unit): Bitmap? {
        return try {
            runModel(input, onProgress)
        } catch (e: Exception) {
            e.printStackTrace()
            // If failed with GPU, try to recreate interpreter on CPU and retry once
            try {
                interpreter?.close()
                interpreter = Interpreter(modelFile, Interpreter.Options())
                runModel(input, onProgress)
            } catch (retryException: Exception) {
                retryException.printStackTrace()
                null
            }
        }
    }

    private fun runModel(input: Bitmap, onProgress: (parts: Int, total: Int) -> Unit): Bitmap? {
        val tflite = interpreter ?: return null
        val upscale = 4
        val tileSize = 256 

        val w = input.width
        val h = input.height
        val outW = w * upscale
        val outH = h * upscale

        val output = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)

        val tilesX = (w + tileSize - 1) / tileSize
        val tilesY = (h + tileSize - 1) / tileSize
        val totalTiles = tilesX * tilesY
        var processedTiles = 0
        
        // Reuse buffer for tiles to reduce GC
        val inputBuffer = ByteBuffer.allocateDirect(1 * tileSize * tileSize * 3 * 4)
        inputBuffer.order(ByteOrder.nativeOrder())
        
        val outputBuffer = ByteBuffer.allocateDirect(1 * (tileSize * upscale) * (tileSize * upscale) * 3 * 4)
        outputBuffer.order(ByteOrder.nativeOrder())

        for (y in 0 until tilesY) {
            for (x in 0 until tilesX) {
                val srcX = x * tileSize
                val srcY = y * tileSize
                val srcW = min(tileSize, w - srcX)
                val srcH = min(tileSize, h - srcY)

                // Extract tile
                val tile = Bitmap.createBitmap(input, srcX, srcY, srcW, srcH)
                val paddedTile = if (srcW != tileSize || srcH != tileSize) {
                    val p = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
                    val c = Canvas(p)
                    c.drawBitmap(tile, 0f, 0f, null)
                    tile.recycle()
                    p
                } else {
                    tile
                }

                // Preprocess
                inputBuffer.rewind()
                val intValues = IntArray(tileSize * tileSize)
                paddedTile.getPixels(intValues, 0, tileSize, 0, 0, tileSize, tileSize)
                for (pixel in intValues) {
                    inputBuffer.putFloat(((pixel shr 16 and 0xFF) / 255.0f))
                    inputBuffer.putFloat(((pixel shr 8 and 0xFF) / 255.0f))
                    inputBuffer.putFloat(((pixel and 0xFF) / 255.0f))
                }
                paddedTile.recycle()

                // Run inference
                outputBuffer.rewind()
                inputBuffer.rewind()
                tflite.run(inputBuffer, outputBuffer)

                // Postprocess
                outputBuffer.rewind()
                val outTileSize = tileSize * upscale
                val outPixels = IntArray(outTileSize * outTileSize)
                for (i in outPixels.indices) {
                    val r = (outputBuffer.float * 255).toInt().coerceIn(0, 255)
                    val g = (outputBuffer.float * 255).toInt().coerceIn(0, 255)
                    val b = (outputBuffer.float * 255).toInt().coerceIn(0, 255)
                    outPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
                
                val outTile = Bitmap.createBitmap(outPixels, outTileSize, outTileSize, Bitmap.Config.ARGB_8888)
                
                // Draw to result
                val dstX = srcX * upscale
                val dstY = srcY * upscale
                val validW = srcW * upscale
                val validH = srcH * upscale
                
                val srcRect = Rect(0, 0, validW, validH)
                val dstRect = Rect(dstX, dstY, dstX + validW, dstY + validH)
                canvas.drawBitmap(outTile, srcRect, dstRect, null)
                outTile.recycle()
                
                processedTiles++
                onProgress(processedTiles, totalTiles)
            }
        }
        
        return output
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
