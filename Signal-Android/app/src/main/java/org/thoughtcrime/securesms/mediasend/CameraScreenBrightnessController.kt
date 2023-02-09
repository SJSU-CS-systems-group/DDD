package org.thoughtcrime.securesms.mediasend

import android.view.Window
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Modifies screen brightness to increase to a max of 66% if lower than that for optimal picture
 * taking conditions. This brightness is only applied when the front-facing camera is selected.
 */
class CameraScreenBrightnessController(
  private val window: Window,
  private val cameraStateProvider: CameraStateProvider
) : DefaultLifecycleObserver {

  companion object {
    private const val FRONT_CAMERA_BRIGHTNESS = 0.66f
  }

  private val originalBrightness: Float by lazy { window.attributes.screenBrightness }

  override fun onResume(owner: LifecycleOwner) {
    onCameraDirectionChanged(cameraStateProvider.isFrontFacingCameraSelected())
    onCameraFlashChanged(cameraStateProvider.isFlashEnabled())
  }

  override fun onPause(owner: LifecycleOwner) {
    disableBrightness()
  }

  /**
   * Because setting camera direction is an asynchronous action, we cannot rely on
   * the `CameraDirectionProvider` at this point.
   */
  fun onCameraDirectionChanged(isFrontFacing: Boolean) {
    if (isFrontFacing && cameraStateProvider.isFlashEnabled()) {
      enableBrightness()
    } else {
      disableBrightness()
    }
  }

  fun onCameraFlashChanged(isFlashEnabled: Boolean) {
    if (isFlashEnabled && cameraStateProvider.isFrontFacingCameraSelected()) {
      enableBrightness()
    } else {
      disableBrightness()
    }
  }

  private fun enableBrightness() {
    if (originalBrightness < FRONT_CAMERA_BRIGHTNESS) {
      window.attributes = window.attributes.apply {
        screenBrightness = FRONT_CAMERA_BRIGHTNESS
      }
    }
  }

  private fun disableBrightness() {
    if (window.attributes.screenBrightness == FRONT_CAMERA_BRIGHTNESS) {
      window.attributes = window.attributes.apply {
        screenBrightness = originalBrightness
      }
    }
  }

  interface CameraStateProvider {
    fun isFrontFacingCameraSelected(): Boolean
    fun isFlashEnabled(): Boolean
  }
}
