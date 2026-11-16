package com.droidhands.android.access

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Expected screenshot failure; the message is agent-facing. */
class ScreenshotException(message: String) : Exception(message)

/** `AccessibilityService.takeScreenshot` (API 30) as a suspend function returning a software bitmap. */
internal object Screenshots {
    private const val RETRIES = 3
    private const val RETRY_DELAY_MS = 400L
    private const val CALLBACK_TIMEOUT_MS = 5_000L

    // The callback only resumes the coroutine, so running it on the binder thread is fine.
    private val direct = Executor { it.run() }

    private sealed interface Outcome {
        class Success(val result: AccessibilityService.ScreenshotResult) : Outcome
        class Failure(val code: Int) : Outcome
    }

    suspend fun capture(service: AccessibilityService): Bitmap {
        var retries = 0
        while (true) {
            when (val outcome = takeOnce(service)) {
                is Outcome.Success -> return withContext(Dispatchers.Default) { toBitmap(outcome.result) }
                is Outcome.Failure -> {
                    if (outcome.code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && retries < RETRIES) {
                        retries++
                        delay(RETRY_DELAY_MS)
                    } else {
                        throw ScreenshotException(describe(outcome.code))
                    }
                }
            }
        }
    }

    private suspend fun takeOnce(service: AccessibilityService): Outcome = withContext(Dispatchers.Main) {
        withTimeoutOrNull(CALLBACK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    direct,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            // If the caller was cancelled meanwhile, release the buffer instead of leaking it.
                            cont.resume(Outcome.Success(screenshot)) { _, _, _ -> screenshot.hardwareBuffer.close() }
                        }

                        override fun onFailure(errorCode: Int) {
                            cont.resume(Outcome.Failure(errorCode))
                        }
                    },
                )
            }
        } ?: throw ScreenshotException("Android did not deliver the screenshot in time. Retry, or use screen_read.")
    }

    private fun toBitmap(result: AccessibilityService.ScreenshotResult): Bitmap {
        val buffer = result.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: throw ScreenshotException("Could not read the screenshot buffer. Retry, or use screen_read.")
            try {
                return hardware.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw ScreenshotException("Not enough memory to process the screenshot. Retry with a smaller max_dimension.")
            } finally {
                hardware.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    private fun describe(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "Android rate-limits screenshots. Wait a second and retry."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "The DroidHands accessibility service is not allowed to take screenshots. Ask the user to turn it off and on again in Settings > Accessibility."
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "The display is not available for screenshots (screen off?). Use screen_read instead."
        else -> "Android could not take the screenshot (error $code). Retry, or use screen_read."
    }
}
