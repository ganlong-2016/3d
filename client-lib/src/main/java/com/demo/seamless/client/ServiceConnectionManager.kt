package com.demo.seamless.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.Surface
import com.demo.seamless.ipc.IGLRenderService
import com.demo.seamless.ipc.IpcConstants

class ServiceConnectionManager(
    private val context: Context,
    private val clientId: String,
) {
    companion object {
        private const val TAG = "ServiceConnection"
    }

    private var service: IGLRenderService? = null
    private var bound = false
    private var pendingSurface: Triple<Surface, Int, Int>? = null

    var onConnected: (() -> Unit)? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IGLRenderService.Stub.asInterface(binder)
            bound = true
            Log.d(TAG, "Connected to GLRenderService")

            try {
                service?.registerClient(clientId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register client", e)
            }

            pendingSurface?.let { (s, w, h) ->
                setSurface(s, w, h)
                pendingSurface = null
            }

            onConnected?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Log.d(TAG, "Disconnected from GLRenderService")
        }
    }

    fun bind() {
        val intent = Intent(IpcConstants.ACTION_BIND_SERVICE).apply {
            setPackage(IpcConstants.HOST_PACKAGE)
        }
        try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.d(TAG, "Binding to GLRenderService...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind", e)
        }
    }

    fun unbind() {
        if (bound) {
            try { service?.unregisterClient(clientId) } catch (_: Exception) { }
            try { context.unbindService(connection) } catch (_: Exception) { }
            bound = false
            service = null
        }
    }

    fun setSurface(surface: Surface, width: Int, height: Int) {
        val svc = service
        if (svc == null) {
            pendingSurface = Triple(surface, width, height)
            return
        }
        try {
            svc.setSurface(clientId, surface, width, height)
        } catch (e: Exception) {
            Log.e(TAG, "setSurface failed", e)
        }
    }

    fun removeSurface() {
        pendingSurface = null
        try {
            service?.removeSurface(clientId)
        } catch (e: Exception) {
            Log.e(TAG, "removeSurface failed", e)
        }
    }

    fun updateCamera(rotX: Float, rotY: Float, dist: Float) {
        try {
            service?.updateCamera(clientId, rotX, rotY, dist)
        } catch (_: Exception) { }
    }

    fun requestTransition(
        targetPkg: String, targetAct: String, durationMs: Long,
        rotX: Float, rotY: Float, dist: Float,
    ) {
        if (service == null) {
            Log.w(TAG, "requestTransition: service NOT connected")
            return
        }
        try {
            service?.requestTransition(clientId, targetPkg, targetAct, durationMs, rotX, rotY, dist)
        } catch (e: Exception) {
            Log.e(TAG, "Transition request failed", e)
        }
    }

    val isConnected: Boolean get() = bound && service != null
}
