package com.demo.seamless.client

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.demo.seamless.ipc.IpcConstants

/**
 * GL Client Screen — renders via remote Service.
 *
 * Creates a SurfaceView and passes its Surface to the GLRenderService via AIDL.
 * The service performs all OpenGL rendering; this composable only handles
 * touch gestures and forwards camera updates to the service.
 */
@Composable
fun GLClientScreen(
    clientId: String,
    title: String,
    targetPkg: String,
    targetAct: String,
    targetLabel: String,
) {
    val context = LocalContext.current
    val connManager = remember {
        ServiceConnectionManager(context, clientId)
    }

    val defaultCfg = IpcConstants.CLIENT_CONFIGS[clientId]
    var rotX by remember { mutableFloatStateOf(defaultCfg?.homeRotX ?: -25f) }
    var rotY by remember { mutableFloatStateOf(defaultCfg?.homeRotY ?: 45f) }
    var dist by remember { mutableFloatStateOf(defaultCfg?.homeDist ?: 3.5f) }

    DisposableEffect(Unit) {
        connManager.bind()
        onDispose {
            connManager.removeSurface()
            connManager.unbind()
        }
    }

    Box(Modifier.fillMaxSize()) {

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {}

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            connManager.setSurface(holder.surface, width, height)
                            connManager.updateCamera(rotX, rotY, dist)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            connManager.removeSurface()
                        }
                    })
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (zoom != 1f) {
                            dist = (dist / zoom).coerceIn(1.5f, 8f)
                        }
                        rotY += pan.x * 0.3f
                        rotX = (rotX + pan.y * 0.3f).coerceIn(-85f, 85f)

                        connManager.updateCamera(rotX, rotY, dist)
                    }
                }
        ) {

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp),
                color = Color.Black.copy(alpha = 0.6f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    title,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(onClick = {
                    connManager.requestTransition(
                        targetPkg, targetAct, 800L,
                        rotX, rotY, dist,
                    )
                }) {
                    Text(targetLabel)
                }

                Surface(
                    color = Color.Black.copy(alpha = 0.55f),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(
                        "单指拖动旋转 · 双指缩放 · 点击按钮一镜到底",
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        color = Color.White.copy(alpha = 0.85f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
