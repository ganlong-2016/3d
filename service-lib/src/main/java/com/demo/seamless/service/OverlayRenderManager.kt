package com.demo.seamless.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Overlay transition manager that uses the shared EglRenderEngine.
 *
 * Instead of creating its own GLSurfaceView + renderer, it creates a SurfaceView
 * inside an overlay window and registers the Surface with the engine as the
 * "overlay" render target. Camera animation drives the target's camera params.
 */
class OverlayRenderManager(
    private val context: Context,
    private val engine: EglRenderEngine,
) {

    companion object {
        private const val TAG = "OverlayRender"
        private const val OVERLAY_TARGET_ID = "__overlay__"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayRoot: FrameLayout? = null
    private var currentAnimator: ValueAnimator? = null

    val isShowing: Boolean get() = overlayRoot != null

    fun canShowOverlay(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    fun performTransition(
        startRotX: Float, startRotY: Float, startDist: Float,
        endRotX: Float, endRotY: Float, endDist: Float,
        durationMs: Long,
        launchTarget: () -> Unit,
    ) {
        if (!canShowOverlay()) {
            Log.w(TAG, "No overlay permission")
            return
        }
        if (isShowing) {
            Log.w(TAG, "Transition already in progress")
            return
        }

        Log.d(TAG, "Transition: ($startRotX,$startRotY,$startDist) → ($endRotX,$endRotY,$endDist) ${durationMs}ms")

        handler.post {
            createOverlay(startRotX, startRotY, startDist)
            launchTarget()
            startCameraAnimation(startRotX, startRotY, startDist, endRotX, endRotY, endDist, durationMs)
        }
    }

    private fun createOverlay(rotX: Float, rotY: Float, dist: Float) {
        val root = FrameLayout(context)
        root.alpha = 0f

        val surfaceView = SurfaceView(context)
        root.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {}

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                engine.addSurface(OVERLAY_TARGET_ID, holder.surface, width, height)
                engine.updateCamera(OVERLAY_TARGET_ID, rotX, rotY, dist)
                handler.postDelayed({ overlayRoot?.alpha = 1f }, 50)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                engine.removeSurface(OVERLAY_TARGET_ID)
            }
        })

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.OPAQUE
        }

        wm.addView(root, params)
        overlayRoot = root
        Log.d(TAG, "Overlay created")
    }

    private fun startCameraAnimation(
        startRotX: Float, startRotY: Float, startDist: Float,
        endRotX: Float, endRotY: Float, endDist: Float,
        durationMs: Long,
    ) {
        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                val rx = startRotX + (endRotX - startRotX) * t
                val ry = startRotY + (endRotY - startRotY) * t
                val d = startDist + (endDist - startDist) * t
                engine.updateCamera(OVERLAY_TARGET_ID, rx, ry, d)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    handler.postDelayed({ removeOverlay() }, 300)
                }
            })
            start()
        }
    }

    private fun removeOverlay() {
        currentAnimator?.cancel()
        currentAnimator = null
        engine.removeSurface(OVERLAY_TARGET_ID)
        overlayRoot?.let {
            try { wm.removeView(it) } catch (_: Exception) { }
        }
        overlayRoot = null
        Log.d(TAG, "Overlay removed")
    }
}
