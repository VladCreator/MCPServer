package com.vlad.mcpserver

import android.content.Context
import android.os.Environment
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Captures a still image with CameraX without a preview. A new instance is
 * created per capture so the camera is released immediately afterwards.
 */
class CameraCapture(private val context: Context) : LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    suspend fun capture(): Result<File> = withContext(Dispatchers.Main) {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            provider.unbindAll()
            provider.bindToLifecycle(
                this@CameraCapture,
                CameraSelector.DEFAULT_BACK_CAMERA,
                imageCapture
            )

            val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: context.filesDir
            directory.mkdirs()
            val file = File(directory, "mcp_${System.currentTimeMillis()}.jpg")

            val result = suspendCancellableCoroutine<Result<File>> { continuation ->
                val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()
                imageCapture.takePicture(
                    outputOptions,
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            continuation.resume(Result.success(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            continuation.resume(Result.failure(exception))
                        }
                    }
                )
            }
            provider.unbindAll()
            result
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (error: Throwable) {
                    continuation.resumeWithException(error)
                }
            },
            ContextCompat.getMainExecutor(context)
        )
    }
}
