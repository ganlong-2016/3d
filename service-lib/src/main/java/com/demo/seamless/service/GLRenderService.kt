package com.demo.seamless.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Surface
import com.demo.seamless.ipc.ClientConfig
import com.demo.seamless.ipc.IGLRenderService
import com.demo.seamless.ipc.IpcConstants

class GLRenderService : Service() {

    companion object {
        private const val TAG = "GLRenderService"
    }

    private val registeredClients = mutableSetOf<String>()
    lateinit var engine: EglRenderEngine
        private set
    private lateinit var overlayManager: OverlayRenderManager

    override fun onCreate() {
        super.onCreate()
        engine = EglRenderEngine(applicationContext)
        engine.start()
        overlayManager = OverlayRenderManager(applicationContext, engine)
        Log.d(TAG, "GLRenderService created")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Client binding, action=${intent?.action}")
        return binder
    }

    private val binder = object : IGLRenderService.Stub() {

        override fun registerClient(clientId: String) {
            synchronized(registeredClients) { registeredClients.add(clientId) }
            Log.d(TAG, "Client registered: $clientId (total=${registeredClients.size})")
        }

        override fun unregisterClient(clientId: String) {
            synchronized(registeredClients) { registeredClients.remove(clientId) }
            engine.removeSurface(clientId)
            Log.d(TAG, "Client unregistered: $clientId (total=${registeredClients.size})")
        }

        override fun setSurface(clientId: String, surface: Surface?, width: Int, height: Int) {
            if (surface == null || !surface.isValid) {
                Log.w(TAG, "setSurface: invalid surface for $clientId")
                return
            }
            val cfg = IpcConstants.CLIENT_CONFIGS[clientId]
            engine.addSurface(clientId, surface, width, height)
            if (cfg != null) {
                engine.updateCamera(clientId, cfg.homeRotX, cfg.homeRotY, cfg.homeDist)
            }
            Log.d(TAG, "Surface set for $clientId ${width}x${height}")
        }

        override fun removeSurface(clientId: String) {
            engine.removeSurface(clientId)
            Log.d(TAG, "Surface removed for $clientId")
        }

        override fun updateCamera(clientId: String, rotX: Float, rotY: Float, dist: Float) {
            engine.updateCamera(clientId, rotX, rotY, dist)
        }

        override fun requestTransition(
            clientId: String,
            targetPkg: String,
            targetAct: String,
            durationMs: Long,
            rotX: Float,
            rotY: Float,
            dist: Float,
        ) {
            Log.d(TAG, "Transition: $clientId → $targetPkg/$targetAct camera=($rotX,$rotY,$dist)")

            if (!overlayManager.canShowOverlay()) {
                Log.w(TAG, "No overlay permission, prompting")
                promptOverlayPermission()
                return
            }

            val targetConfig = findTargetConfig(clientId)
            if (targetConfig == null) {
                Log.e(TAG, "Unknown target config for $clientId, launching directly")
                launchDirectly(targetPkg, targetAct)
                return
            }

            overlayManager.performTransition(
                startRotX = rotX,
                startRotY = rotY,
                startDist = dist,
                endRotX = targetConfig.homeRotX,
                endRotY = targetConfig.homeRotY,
                endDist = targetConfig.homeDist,
                durationMs = durationMs,
                launchTarget = { launchDirectly(targetPkg, targetAct) },
            )
        }
    }

    private fun findTargetConfig(clientId: String): ClientConfig? {
        val myConfig = IpcConstants.CLIENT_CONFIGS[clientId] ?: return null
        return IpcConstants.CLIENT_CONFIGS[myConfig.targetClientId]
    }

    private fun launchDirectly(targetPkg: String, targetAct: String) {
        try {
            val intent = Intent().apply {
                setClassName(targetPkg, targetAct)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch $targetPkg/$targetAct", e)
        }
    }

    private fun promptOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${applicationContext.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open overlay permission settings", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        engine.shutdown()
        Log.d(TAG, "GLRenderService destroyed")
    }
}
