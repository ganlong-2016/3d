package com.demo.seamless.client

import android.opengl.GLSurfaceView
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
import com.demo.a3ddemo.renderer.LitCubeRenderer
import com.demo.seamless.ipc.IpcConstants

/**
 * GL Client Composable — Client App 的核心 UI 组件
 *
 * 封装了：
 *   1. GLSurfaceView + LitCubeRenderer 渲染 3D 模型
 *   2. 触控手势（旋转/缩放）——同步到渲染器摄像机
 *   3. 与 Service Host 的连接管理
 *   4. 一镜到底过渡按钮（把当前摄像机状态发给 Service）
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
        ServiceConnectionManager(context, clientId).also { it.bind() }
    }

    val defaultCfg = IpcConstants.CLIENT_CONFIGS[clientId]
    var rotX by remember { mutableFloatStateOf(defaultCfg?.homeRotX ?: -25f) }
    var rotY by remember { mutableFloatStateOf(defaultCfg?.homeRotY ?: 45f) }
    var dist by remember { mutableFloatStateOf(defaultCfg?.homeDist ?: 3.5f) }

    val renderer = remember {
        LitCubeRenderer(context).apply {
            autoRotate = false
            rotationX = defaultCfg?.homeRotX ?: -25f
            rotationY = defaultCfg?.homeRotY ?: 45f
            cameraDistance = defaultCfg?.homeDist ?: 3.5f
        }
    }

    DisposableEffect(Unit) {
        onDispose { connManager.unbind() }
    }

    Box(Modifier.fillMaxSize()) {

        // Layer 1: OpenGL 3D 渲染（最底层）
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 2: 透明手势层 + UI 覆盖在 GL 上方
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

                        renderer.rotationX = rotX
                        renderer.rotationY = rotY
                        renderer.cameraDistance = dist
                    }
                }
        ) {

            // 顶部标题
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

            // 底部过渡按钮
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
