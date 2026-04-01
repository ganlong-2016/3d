package com.demo.seamless.service

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.demo.a3ddemo.renderer.LitCubeRenderer

/**
 * 一镜到底 Overlay 渲染管理器
 *
 * 核心原理：
 *   1. 创建 TYPE_APPLICATION_OVERLAY 全屏窗口，内含 GLSurfaceView
 *   2. GLSurfaceView 以发起方当前摄像机角度开始渲染同一模型
 *   3. 摄像机平滑轨道旋转到目标客户端的主视角（DecelerateInterpolator）
 *   4. 动画期间在 overlay 遮挡下启动目标 Activity
 *   5. 动画结束 + 目标就绪后移除 overlay
 *
 * 用户视角：同一个 3D 模型从一个角度平滑旋转到另一个角度，看不到 App 切换。
 */
class OverlayRenderManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayRender"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlayRoot: FrameLayout? = null
    private var glSurfaceView: GLSurfaceView? = null
    private var renderer: LitCubeRenderer? = null
    private var currentAnimator: ValueAnimator? = null

    val isShowing: Boolean get() = overlayRoot != null

    fun canShowOverlay(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
        Log.d(TAG, "canShowOverlay=$result, package=${context.packageName}")
        return result
    }

    /**
     * 执行一镜到底过渡
     *
     * @param startRotX   发起方当前摄像机 rotationX
     * @param startRotY   发起方当前摄像机 rotationY
     * @param startDist   发起方当前摄像机 cameraDistance
     * @param endRotX     目标客户端主视角 rotationX
     * @param endRotY     目标客户端主视角 rotationY
     * @param endDist     目标客户端主视角 cameraDistance
     * @param durationMs  摄像机轨道动画时长
     * @param launchTarget 在动画开始时调用，启动目标 Activity
     */
    fun performTransition(
        startRotX: Float, startRotY: Float, startDist: Float,
        endRotX: Float, endRotY: Float, endDist: Float,
        durationMs: Long,
        launchTarget: () -> Unit,
    ) {
        if (!canShowOverlay()) {
            Log.w(TAG, "No overlay permission, cannot perform transition")
            return
        }
        if (isShowing) {
            Log.w(TAG, "Transition already in progress")
            return
        }

        Log.d(TAG, "Transition: camera ($startRotX,$startRotY,$startDist) → ($endRotX,$endRotY,$endDist) ${durationMs}ms")

        handler.post {
            createOverlay(startRotX, startRotY, startDist)
            launchTarget()
            startCameraAnimation(endRotX, endRotY, endDist, durationMs)
        }
    }

    private fun createOverlay(rotX: Float, rotY: Float, dist: Float) {
        val root = FrameLayout(context)

        val r = LitCubeRenderer(context).apply {
            autoRotate = false
            rotationX = rotX
            rotationY = rotY
            cameraDistance = dist
        }
        renderer = r

        val gl = GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setRenderer(r)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        glSurfaceView = gl
        root.addView(gl, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))

        // overlay 初始不可见，等 GL 渲染出第一帧再显示，避免黑闪
        root.alpha = 0f
        r.onFirstFrameReady = Runnable {
            handler.post { overlayRoot?.alpha = 1f }
        }

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
        Log.d(TAG, "Overlay created at camera ($rotX, $rotY, $dist)")
    }

    private fun startCameraAnimation(
        endRotX: Float, endRotY: Float, endDist: Float, durationMs: Long,
    ) {
        val r = renderer ?: return
        val startRotX = r.rotationX
        val startRotY = r.rotationY
        val startDist = r.cameraDistance

        currentAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                r.rotationX = startRotX + (endRotX - startRotX) * t
                r.rotationY = startRotY + (endRotY - startRotY) * t
                r.cameraDistance = startDist + (endDist - startDist) * t
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // 动画结束后延迟移除，让目标 Activity 的 GL 渲染就绪
                    handler.postDelayed({ removeOverlay() }, 300)
                }
            })
            start()
        }
    }

    private fun removeOverlay() {
        currentAnimator?.cancel()
        currentAnimator = null
        glSurfaceView?.onPause()
        overlayRoot?.let {
            try {
                wm.removeView(it)
            } catch (_: Exception) { }
        }
        overlayRoot = null
        glSurfaceView = null
        renderer = null
        Log.d(TAG, "Overlay removed")
    }
}
