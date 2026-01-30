package org.koitharu.kotatsu.reader.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.MappedByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

import org.koitharu.kotatsu.core.model.UpscaleModel

@Singleton
class UpscaleEngine @Inject constructor(
	@ApplicationContext private val context: Context
) {

	private var interpreter: Interpreter? = null
	private var gpuDelegate: GpuDelegate? = null
	private var currentModelPath: String? = null
	
	// Model-specific constants (to be refined based on actual model)
	private val inputSize = 128 
	private val upscaleFactor = 4
	private val overlap = 16
	
	private suspend fun ensureInterpreter(model: UpscaleModel): Interpreter? = withContext(Dispatchers.IO) {
		val modelPath = when (model) {
			UpscaleModel.FAST -> "models/waifu2x_fast.tflite"
			UpscaleModel.ELITE -> "models/esrgan_elite.tflite"
		}

		if (interpreter != null && currentModelPath == modelPath) return@withContext interpreter
		
		release() // Release old interpreter if model changed
		
		try {
			val assets = context.assets.list("models") ?: emptyArray()
			if (!assets.contains(modelPath.substringAfterLast("/"))) {
				return@withContext null
			}
			val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelPath)
			val options = Interpreter.Options().apply {
				try {
					gpuDelegate = GpuDelegate()
					addDelegate(gpuDelegate)
				} catch (e: Exception) {
					// Fallback to CPU if GPU is unavailable
				}
				setNumThreads(4)
			}
			interpreter = Interpreter(modelBuffer, options)
			currentModelPath = modelPath
			interpreter
		} catch (e: Exception) {
			null
		}
	}

	suspend fun upscale(bitmap: Bitmap, model: UpscaleModel): Bitmap = withContext(Dispatchers.Default) {
		val engine = ensureInterpreter(model) ?: return@withContext bitmap
		
		val width = bitmap.width
		val height = bitmap.height
		
		val outputWidth = width * upscaleFactor
		val outputHeight = height * upscaleFactor
		
		val resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(resultBitmap)
		
		// Tiling Logic
		val effectiveInputSize = inputSize - 2 * overlap
		val numTilesX = ceil(width.toDouble() / effectiveInputSize).toInt()
		val numTilesY = ceil(height.toDouble() / effectiveInputSize).toInt()
		
		for (y in 0 until numTilesY) {
			for (x in 0 until numTilesX) {
				val srcLeft = x * effectiveInputSize - overlap
				val srcTop = y * effectiveInputSize - overlap
				val srcRight = srcLeft + inputSize
				val srcBottom = srcTop + inputSize
				
				// Clamp to bitmap bounds
				val actualSrcLeft = srcLeft.coerceAtLeast(0)
				val actualSrcTop = srcTop.coerceAtLeast(0)
				val actualSrcRight = srcRight.coerceAtMost(width)
				val actualSrcBottom = srcBottom.coerceAtMost(height)
				
				val tileRect = Rect(actualSrcLeft, actualSrcTop, actualSrcRight, actualSrcBottom)
				val tileBitmap = Bitmap.createBitmap(bitmap, tileRect.left, tileRect.top, tileRect.width(), tileRect.height())
				
				// Pre-process tile to fit model input exactly
				val processedTile = processTile(tileBitmap, engine) ?: tileBitmap
				
				// Calculate destination on high-res canvas
				// We need to account for the overlap we added for context
				val destLeft = (x * effectiveInputSize) * upscaleFactor
				val destTop = (y * effectiveInputSize) * upscaleFactor
				
				// Crop the overlap from the upscaled tile
				val cropLeft = (actualSrcLeft - srcLeft) * upscaleFactor
				val cropTop = (actualSrcTop - srcTop) * upscaleFactor
				val cropWidth = (tileRect.width() - (if (srcRight > width) srcRight - width else 0) - (if (srcLeft < 0) -srcLeft else 0)) * upscaleFactor
				val cropHeight = (tileRect.height() - (if (srcBottom > height) srcBottom - height else 0) - (if (srcTop < 0) -srcTop else 0)) * upscaleFactor
				
				// Simplified stitching for prototype:
				canvas.drawBitmap(processedTile, destLeft.toFloat(), destTop.toFloat(), null)
				
				tileBitmap.recycle()
				if (processedTile != tileBitmap) processedTile.recycle()
			}
		}
		
		resultBitmap
	}

	private fun processTile(tile: Bitmap, engine: Interpreter): Bitmap? {
		// 1. Prepare Input
		val tensorImage = TensorImage(engine.getInputTensor(0).dataType())
		tensorImage.load(tile)
		
		// Model expects inputSize x inputSize
		val imageProcessor = ImageProcessor.Builder()
			.add(ResizeOp(inputSize, inputSize, ResizeOp.ResizeMethod.BILINEAR))
			// Normalization: often SR models expect [0, 1]
			// .add(NormalizeOp(0f, 255f)) 
			.build()
		
		val processedImage = imageProcessor.process(tensorImage)
		
		// 2. Prepare Output
		val outputWidth = inputSize * upscaleFactor
		val outputHeight = inputSize * upscaleFactor
		val outputBuffer = TensorBuffer.createFixedSize(intArrayOf(1, outputHeight, outputWidth, 3), engine.getOutputTensor(0).dataType())
		
		// 3. Run Inference
		engine.run(processedImage.buffer, outputBuffer.buffer)
		
		// 4. Post-process to Bitmap
		val resultBitmap = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
		val pixels = IntArray(outputWidth * outputHeight)
		val outputArray = outputBuffer.floatArray
		
		for (i in 0 until outputHeight * outputWidth) {
			val r = (outputArray[i * 3] * 255).toInt().coerceIn(0, 255)
			val g = (outputArray[i * 3 + 1] * 255).toInt().coerceIn(0, 255)
			val b = (outputArray[i * 3 + 2] * 255).toInt().coerceIn(0, 255)
			pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
		}
		
		resultBitmap.setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
		return resultBitmap
	}

	fun release() {
		interpreter?.close()
		interpreter = null
		gpuDelegate?.close()
		gpuDelegate = null
	}
}
