package com.demo.a3ddemo

import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.demo.a3ddemo.gl.DemoRenderer
import com.demo.a3ddemo.renderer.BasicCubeRenderer
import com.demo.a3ddemo.renderer.LitCubeRenderer
import com.demo.a3ddemo.renderer.ModelRenderer
import com.demo.a3ddemo.renderer.SkyboxCubeRenderer
import com.demo.a3ddemo.renderer.SkyboxRendererES20
import com.demo.a3ddemo.renderer.TexturedCubeRenderer
import com.demo.a3ddemo.ui.theme._3ddemoTheme
import kotlin.math.sqrt

/**
 * 主 Activity — 3D 原理演示应用
 *
 * 应用结构：
 *   - 主页面：4 张卡片，对应文档的 4 个演示阶段
 *   - 演示页面：全屏 OpenGL ES 3.0 渲染 + 触控交互
 *
 * 触控交互：
 *   - 单指拖动 → 旋转正方体（修改 rotationX / rotationY）
 *   - 双指捏合 → 缩放视图（修改 cameraDistance）
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            _3ddemoTheme {
                // 当前选中的演示索引，null 表示在主页面
                var currentDemo by remember { mutableStateOf<Int?>(null) }

                // 系统返回键处理
                BackHandler(enabled = currentDemo != null) { currentDemo = null }

                if (currentDemo == null) {
                    DemoListScreen(onSelect = { currentDemo = it })
                } else {
                    DemoViewScreen(index = currentDemo!!, onBack = { currentDemo = null })
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 演示信息数据 — 每个 Demo 对应文档中的一个核心概念
// ══════════════════════════════════════════════════════════════

private data class DemoInfo(
    val step: String,        // 步骤编号
    val title: String,       // 标题
    val concepts: String,    // 涉及的 3D 概念
    val description: String, // 详细说明（引用文档内容）
    val tip: String,         // 交互提示
)

private val demos = listOf(
    DemoInfo(
        step = "第一步",
        title = "白车身 — 用三角形拼出形状",
        concepts = "Mesh · 顶点(Vertex) · 三角形 · MVP 变换",
        description = "正方体由 24 个顶点、12 个三角形拼成。每个顶点有 (x, y, z) 坐标，" +
                "每个面由 2 个三角形组成。通过模型-视图-投影 (MVP) 矩阵变换，3D 坐标投影到 2D 屏幕。" +
                "不同颜色的面让你看清每个面的三角形拼接方式。",
        tip = "单指拖动旋转 · 双指捏合缩放",
    ),
    DemoInfo(
        step = "第二步",
        title = "贴车衣 — 纹理映射",
        concepts = "Texture(纹理) · UV 映射",
        description = "纹理是「贴车衣」—— 把 2D 图片贴到 3D 表面。" +
                "每个顶点记录 (u, v) 坐标，标记它对应纹理图上的哪个位置。" +
                "棋盘格纹理的蓝色边框和红色十字线帮助你看清 UV 映射的对应关系。",
        tip = "单指拖动旋转 · 双指捏合缩放",
    ),
    DemoInfo(
        step = "第三步",
        title = "展厅打灯 — 光照与材质",
        concepts = "Light(灯光) · Material(材质) · Shader(着色器) · Blinn-Phong",
        description = "Shader（喷漆机器人）根据材质属性和灯光信息，逐像素计算颜色。" +
                "平行光从右上方照射，产生漫反射(Diffuse)让表面有明暗过渡，" +
                "镜面反射(Specular)产生高光点 —— 让物体有真实的立体感。",
        tip = "单指拖动旋转 · 双指捏合缩放",
    ),
    DemoInfo(
        step = "第四步",
        title = "搭展厅 — 天空盒与环境反射",
        concepts = "Skybox(天空盒) · CubeMap(立方体贴图) · 环境反射",
        description = "天空盒是展厅四周的巨幅背景画，用 6 张图拼成一个包围盒。" +
                "正方体表面反射天空盒图像，就像车漆倒映周围环境。" +
                "这就是为什么 3D 车漆看起来能反射出「周围世界」。",
        tip = "单指拖动旋转 · 双指捏合缩放",
    ),
    DemoInfo(
        step = "第五步",
        title = "点线面 + 概念可视化",
        concepts = "Mesh · 面数 · 坐标轴 · 视锥体 · 法线光照 · 波浪面",
        description = "11 个模型分两组：①~⑥ 从四面体到环形体，面数递增展示精细度差异；" +
                "⑦~⑪ 概念可视化——坐标轴(坐标变换)、箭头(方向向量)、" +
                "视锥体(相机投影)、楼梯(法线决定光照)、波浪面(Blinn-Phong 明暗)。" +
                "点击「下一个模型」逐一切换。",
        tip = "单指拖动旋转 · 双指捏合缩放",
    ),
    DemoInfo(
        step = "番外篇",
        title = "ES 2.0 自包含天空盒",
        concepts = "OpenGL ES 2.0 · 单文件实现 · attribute/varying 语法",
        description = "用 OpenGL ES 2.0 重新实现天空盒 + 环境反射，所有代码（Shader 源码、" +
                "纹理生成、编译链接）写在一个类里，不依赖任何工具类。" +
                "对比第四步可以看到 ES 2.0 与 3.0 的语法差异：" +
                "attribute/varying vs in/out，textureCube vs texture。",
        tip = "单指拖动旋转 · 双指捏合缩放",
    ),
)

// ══════════════════════════════════════════════════════════════
// Compose 界面
// ══════════════════════════════════════════════════════════════

/** 演示列表主页面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DemoListScreen(onSelect: (Int) -> Unit) {
    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("3D 原理演示", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(demos) { index, info ->
                DemoCard(info = info, onClick = { onSelect(index) })
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/** 单个演示卡片 */
@Composable
private fun DemoCard(info: DemoInfo, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 步骤标签
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    info.step,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(10.dp))

            // 标题
            Text(
                info.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))

            // 涉及的 3D 概念
            Text(
                info.concepts,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(10.dp))

            // 详细描述
            Text(
                info.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            // 开始按钮
            Button(
                onClick = onClick,
                modifier = Modifier.align(Alignment.End),
            ) {
                Text("开始演示")
            }
        }
    }
}

/** 全屏演示页面：OpenGL 渲染 + 浮动控件 */
@Composable
private fun DemoViewScreen(index: Int, onBack: () -> Unit) {
    val info = demos[index]
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        // ─── OpenGL 渲染视图 ───
        val renderer = remember { createRenderer(context, index) }
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    val glVersion = if (index == 5) 2 else 3
                    setEGLContextClientVersion(glVersion)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY  // 持续渲染（60fps）

                    // ─── 触控手势处理 ───
                    // 单指拖动：旋转（修改 rotationX / rotationY）
                    // 双指捏合：缩放（修改 cameraDistance）
                    var prevX = 0f
                    var prevY = 0f
                    var prevSpan = 0f  // 上一帧两指间距

                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                // 记录单指按下位置
                                prevX = event.x
                                prevY = event.y
                            }

                            MotionEvent.ACTION_POINTER_DOWN -> {
                                // 第二根手指按下，开始缩放
                                if (event.pointerCount >= 2) {
                                    prevSpan = fingerSpan(event)
                                }
                            }

                            MotionEvent.ACTION_MOVE -> {
                                if (event.pointerCount >= 2) {
                                    // ── 双指缩放 ──
                                    val span = fingerSpan(event)
                                    if (prevSpan > 10f) {
                                        // span 变大 → 手指张开 → 拉近（距离变小）
                                        val factor = span / prevSpan
                                        renderer.cameraDistance = (renderer.cameraDistance / factor)
                                            .coerceIn(1.5f, 8f)
                                    }
                                    prevSpan = span
                                } else {
                                    // ── 单指旋转 ──
                                    val dx = event.x - prevX
                                    val dy = event.y - prevY
                                    renderer.rotationY += dx * 0.3f
                                    renderer.rotationX += dy * 0.3f
                                    renderer.rotationX = renderer.rotationX.coerceIn(-85f, 85f)
                                }
                                prevX = event.x
                                prevY = event.y
                            }

                            MotionEvent.ACTION_POINTER_UP -> {
                                // 一根手指抬起 → 重置缩放状态
                                prevSpan = 0f
                                // 防止切换回单指时位置跳跃
                                val remaining = if (event.actionIndex == 0) 1 else 0
                                if (remaining < event.pointerCount) {
                                    prevX = event.getX(remaining)
                                    prevY = event.getY(remaining)
                                }
                            }
                        }
                        v.performClick()
                        true
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // ─── 顶部浮层：返回按钮 + 标题 ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = Color.White,
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "${info.step}：${info.title}",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // ─── 底部浮层 ───
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Demo 5 专属：模型信息 + 切换按钮
            if (index == 4) {
                val mr = renderer as? ModelRenderer
                var modelLabel by remember { mutableStateOf("") }
                LaunchedEffect(mr) {
                    while (true) {
                        modelLabel = mr?.modelInfo ?: ""
                        delay(200)
                    }
                }
                if (modelLabel.isNotEmpty()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text(
                            modelLabel,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Button(onClick = { mr?.nextModel() }) {
                    Text("下一个模型 →")
                }
            }
            // 交互提示
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    info.tip,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════════
// 工具函数
// ══════════════════════════════════════════════════════════════

/** 根据 Demo 索引创建对应的渲染器 */
private fun createRenderer(context: android.content.Context, index: Int): DemoRenderer =
    when (index) {
        0 -> BasicCubeRenderer(context)
        1 -> TexturedCubeRenderer(context)
        2 -> LitCubeRenderer(context)
        3 -> SkyboxCubeRenderer(context)
        4 -> ModelRenderer(context)
        5 -> SkyboxRendererES20()
        else -> BasicCubeRenderer(context)
    }

/** 计算两指之间的距离（用于捏合缩放手势） */
private fun fingerSpan(event: MotionEvent): Float {
    if (event.pointerCount < 2) return 0f
    val dx = event.getX(0) - event.getX(1)
    val dy = event.getY(0) - event.getY(1)
    return sqrt(dx * dx + dy * dy)
}
