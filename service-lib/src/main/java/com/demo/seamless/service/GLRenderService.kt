package com.demo.seamless.service

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.demo.seamless.ipc.ClientConfig
import com.demo.seamless.ipc.IGLRenderService
import com.demo.seamless.ipc.IpcConstants

class GLRenderService : Service() {

    companion object {
        private const val TAG = "GLRenderService"
    }

    private val registeredClients = mutableSetOf<String>()
    private lateinit var overlayManager: OverlayRenderManager

    override fun onCreate() {
        super.onCreate()
        overlayManager = OverlayRenderManager(applicationContext)
        Log.d(TAG, "GLRenderService created, package=${applicationContext.packageName}")
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Client binding, action=${intent?.action}")
        return binder
    }

    private val binder = object : IGLRenderService.Stub() {

        override fun registerClient(clientId: String) {
            synchronized(registeredClients) {
                registeredClients.add(clientId)
            }
            Log.d(TAG, "Client registered: $clientId (total=${registeredClients.size})")
        }

        override fun unregisterClient(clientId: String) {
            synchronized(registeredClients) {
                registeredClients.remove(clientId)
            }
            Log.d(TAG, "Client unregistered: $clientId (total=${registeredClients.size})")
        }

        override fun requestTransition(
            clientId: String,
            targetPkg: String,
            targetAct: String,
            durationMs: Long,
            currentRotX: Float,
            currentRotY: Float,
            currentDist: Float,
        ) {
            Log.d(TAG, "Transition: $clientId → $targetPkg/$targetAct camera=($currentRotX,$currentRotY,$currentDist)")

            if (!overlayManager.canShowOverlay()) {
                Log.w(TAG, "No overlay permission for ${applicationContext.packageName}, prompting")
                promptOverlayPermission()
                return
            }

            // 查找目标客户端的主视角
            val targetConfig = findTargetConfig(clientId)
            if (targetConfig == null) {
                Log.e(TAG, "Unknown target config for $clientId, launching directly")
                launchDirectly(targetPkg, targetAct)
                return
            }

            overlayManager.performTransition(
                startRotX = currentRotX,
                startRotY = currentRotY,
                startDist = currentDist,
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
        Log.d(TAG, "GLRenderService destroyed")
    }
}
