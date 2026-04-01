package com.demo.seamless.service

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import com.demo.a3ddemo.renderer.LitCubeRenderer

/**
 * Cross-process EGL rendering engine.
 *
 * Manages a single EGL context and renders the same 3D model onto multiple
 * client-provided Surfaces. Each client registers a Surface via AIDL;
 * the engine creates an EGLSurface from it and draws every vsync.
 */
class EglRenderEngine(private val context: Context) {

    companion object {
        private const val TAG = "EglRenderEngine"
        private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098
        private const val EGL_OPENGL_ES3_BIT = 0x0040
    }

    private val renderThread = HandlerThread("GLRenderThread").apply { start() }
    private val handler = Handler(renderThread.looper)

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglConfig: EGLConfig? = null

    private var renderer: LitCubeRenderer? = null
    private var glInitialized = false
    private var renderLoopScheduled = false

    private val targets = LinkedHashMap<String, RenderTarget>()

    class RenderTarget(
        val surface: Surface,
        var eglSurface: EGLSurface,
        var width: Int,
        var height: Int,
        @Volatile var rotX: Float = -25f,
        @Volatile var rotY: Float = 45f,
        @Volatile var dist: Float = 3.5f,
        @Volatile var autoRotate: Boolean = false,
    )

    fun start() {
        handler.post { initEgl() }
    }

    fun shutdown() {
        handler.post {
            stopRenderLoop()
            for ((id, _) in targets.toMap()) {
                destroyTarget(id)
            }
            renderer = null
            glInitialized = false
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
        }
        renderThread.quitSafely()
    }

    fun addSurface(targetId: String, surface: Surface, width: Int, height: Int) {
        handler.post {
            ensureEglReady()
            destroyTarget(targetId)

            val eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                Log.e(TAG, "eglCreateWindowSurface failed for $targetId: 0x${Integer.toHexString(EGL14.eglGetError())}")
                return@post
            }

            targets[targetId] = RenderTarget(surface, eglSurface, width, height)
            Log.d(TAG, "Surface added: $targetId ${width}x${height} (total=${targets.size})")
            startRenderLoop()
        }
    }

    fun removeSurface(targetId: String) {
        handler.post {
            destroyTarget(targetId)
            if (targets.isEmpty()) stopRenderLoop()
        }
    }

    fun updateCamera(targetId: String, rotX: Float, rotY: Float, dist: Float) {
        targets[targetId]?.let {
            it.rotX = rotX
            it.rotY = rotY
            it.dist = dist
        }
    }

    fun setAutoRotate(targetId: String, enabled: Boolean) {
        targets[targetId]?.autoRotate = enabled
    }

    fun getTarget(targetId: String): RenderTarget? = targets[targetId]

    // --- internals ---

    private fun initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }

        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) { "eglChooseConfig failed" }
        eglConfig = configs[0]!!

        val contextAttribs = intArrayOf(EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        Log.d(TAG, "EGL initialized: display=$eglDisplay context=$eglContext")
    }

    private fun ensureEglReady() {
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) initEgl()
    }

    private fun ensureRendererReady(eglSurface: EGLSurface) {
        if (!glInitialized) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            renderer = LitCubeRenderer(context).apply { autoRotate = false }
            renderer!!.onSurfaceCreated(null, null)
            glInitialized = true
            Log.d(TAG, "Renderer initialized")
        }
    }

    private fun startRenderLoop() {
        if (renderLoopScheduled) return
        renderLoopScheduled = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopRenderLoop() {
        renderLoopScheduled = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!renderLoopScheduled || targets.isEmpty()) {
                renderLoopScheduled = false
                return
            }
            renderAllTargets()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun renderAllTargets() {
        val r = renderer
        for ((id, target) in targets.toMap()) {
            if (!target.surface.isValid) {
                Log.w(TAG, "Surface invalid for $id, removing")
                destroyTarget(id)
                continue
            }
            try {
                EGL14.eglMakeCurrent(eglDisplay, target.eglSurface, target.eglSurface, eglContext)
                ensureRendererReady(target.eglSurface)

                val rr = renderer ?: continue
                rr.rotationX = target.rotX
                rr.rotationY = target.rotY
                rr.cameraDistance = target.dist
                rr.autoRotate = target.autoRotate
                rr.onSurfaceChanged(null, target.width, target.height)
                rr.onDrawFrame(null)

                EGL14.eglSwapBuffers(eglDisplay, target.eglSurface)
            } catch (e: Exception) {
                Log.e(TAG, "Render error for $id", e)
                destroyTarget(id)
            }
        }
        if (targets.isEmpty()) stopRenderLoop()
    }

    private fun destroyTarget(targetId: String) {
        val target = targets.remove(targetId) ?: return
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && target.eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, eglContext)
            EGL14.eglDestroySurface(eglDisplay, target.eglSurface)
        }
        Log.d(TAG, "Surface removed: $targetId (remaining=${targets.size})")
    }
}
