package com.demo.a3ddemo.renderer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.demo.a3ddemo.gl.DemoRenderer
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * ═══════════════════════════════════════════════════════════════════════
 * 天空盒(Skybox) + 环境反射正方体 —— 完全自包含的 OpenGL ES 2.0 实现
 * ═══════════════════════════════════════════════════════════════════════
 *
 * 这个类用一个文件实现了完整的天空盒渲染，不依赖任何外部工具类。
 * 方便独立阅读和分享「从零实现天空盒」的全部步骤。
 *
 * ─── 它在做什么？───
 *
 *   想象你在一个展厅里看一辆车：
 *     • 天空盒 = 展厅四周墙上的巨幅背景画（日落渐变天空）
 *     • 反射正方体 = 一辆表面光滑的展车，车漆能倒映出周围环境
 *
 * ─── 技术原理 ───
 *
 *   天空盒：一个超大的立方体从内部包围整个场景。
 *     • 用 CubeMap（立方体贴图）= 6 张图拼成的 360° 环境图
 *     • 渲染时关闭深度测试，让它永远在最远处
 *     • 去掉视图矩阵的平移，让它像在「无限远处」
 *
 *   环境反射：正方体表面「倒映」周围环境。
 *     • 计算入射方向 I = normalize(表面点 - 摄像机)
 *     • 用 reflect(I, 法线) 得到反射方向 R
 *     • 用反射方向 R 从同一张 CubeMap 采样颜色
 *
 * ─── 渲染流程（每帧执行）───
 *
 *   ① 计算摄像机轨道位置（球面坐标）
 *   ② 第一遍 Draw：渲染天空盒背景（关闭深度测试）
 *   ③ 第二遍 Draw：渲染反射正方体（开启深度测试）
 *
 * ─── OpenGL ES 2.0 vs 3.0 的区别 ───
 *
 *   ES 2.0                          ES 3.0
 *   attribute vec3 aPos;            layout(location=0) in vec3 aPos;
 *   varying vec3 vDir;              out vec3 vDir; / in vec3 vDir;
 *   gl_FragColor = ...;             out vec4 fragColor; fragColor = ...;
 *   textureCube(sampler, dir)       texture(sampler, dir)
 *   GLES20.glXxx()                  GLES30.glXxx()
 *
 * ─── 使用方式 ───
 *
 *   val glView = GLSurfaceView(context).apply {
 *       setEGLContextClientVersion(2)          // ← 注意是 2 不是 3
 *       setRenderer(SkyboxRendererES20())
 *       renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
 *   }
 *
 * ═══════════════════════════════════════════════════════════════════════
 */
class SkyboxRendererES20 : DemoRenderer {

    // 触控交互状态（从外部设置，线程安全）
    @Volatile override var rotationX = -15f   // 上下视角
    @Volatile override var rotationY = 45f    // 左右视角
    @Volatile override var cameraDistance = 3.5f  // 摄像机到原点的距离（双指缩放）

    // ╔═══════════════════════════════════════════════════════════════╗
    // ║  第一步：准备顶点数据                                         ║
    // ║                                                              ║
    // ║  3D 世界里一切形状都由三角形拼成。                              ║
    // ║  「顶点(Vertex) + 三角形(Triangle) = 网格(Mesh) = 3D 形状」   ║
    // ╚═══════════════════════════════════════════════════════════════╝

    /**
     * 天空盒顶点数据 —— 一个边长为 2 的立方体（中心在原点）
     *
     * 只需要位置坐标(x, y, z)，不需要法线和 UV，
     * 因为 CubeMap 是用方向向量采样的，而顶点位置本身就是方向。
     *
     * 6 个面 × 每面 2 个三角形 × 每三角形 3 个顶点 = 36 个顶点
     */
    private val skyboxVertices = floatArrayOf(
        // +X 面（右）
         1f,-1f,-1f,   1f,-1f, 1f,   1f, 1f, 1f,   1f, 1f, 1f,   1f, 1f,-1f,   1f,-1f,-1f,
        // -X 面（左）
        -1f,-1f, 1f,  -1f,-1f,-1f,  -1f, 1f,-1f,  -1f, 1f,-1f,  -1f, 1f, 1f,  -1f,-1f, 1f,
        // +Y 面（顶）
        -1f, 1f, 1f,  -1f, 1f,-1f,   1f, 1f,-1f,   1f, 1f,-1f,   1f, 1f, 1f,  -1f, 1f, 1f,
        // -Y 面（底）
        -1f,-1f,-1f,  -1f,-1f, 1f,   1f,-1f, 1f,   1f,-1f, 1f,   1f,-1f,-1f,  -1f,-1f,-1f,
        // +Z 面（后）
        -1f,-1f, 1f,  -1f, 1f, 1f,   1f, 1f, 1f,   1f, 1f, 1f,   1f,-1f, 1f,  -1f,-1f, 1f,
        // -Z 面（前）
         1f,-1f,-1f,   1f, 1f,-1f,  -1f, 1f,-1f,  -1f, 1f,-1f,  -1f,-1f,-1f,   1f,-1f,-1f,
    )

    /**
     * 反射正方体顶点数据 —— 位置(3) + 法线(3)
     *
     * 法线(Normal) = 表面朝向，是计算反射方向的关键输入。
     * 每个面有 4 个顶点，6 个面 = 24 个顶点。
     * 同一个角的不同面使用不同法线，所以需要 24 个而不是 8 个。
     *
     * 数据布局: [x, y, z, nx, ny, nz, x, y, z, nx, ny, nz, ...]
     */
    private val cubeVertices = floatArrayOf(
        //  位置 x,y,z           法线 nx,ny,nz
        // ── 前面 (法线朝 +Z) ──
        -0.5f,-0.5f, 0.5f,   0f, 0f, 1f,
         0.5f,-0.5f, 0.5f,   0f, 0f, 1f,
         0.5f, 0.5f, 0.5f,   0f, 0f, 1f,
        -0.5f, 0.5f, 0.5f,   0f, 0f, 1f,
        // ── 后面 (法线朝 -Z) ──
         0.5f,-0.5f,-0.5f,   0f, 0f,-1f,
        -0.5f,-0.5f,-0.5f,   0f, 0f,-1f,
        -0.5f, 0.5f,-0.5f,   0f, 0f,-1f,
         0.5f, 0.5f,-0.5f,   0f, 0f,-1f,
        // ── 左面 (法线朝 -X) ──
        -0.5f,-0.5f,-0.5f,  -1f, 0f, 0f,
        -0.5f,-0.5f, 0.5f,  -1f, 0f, 0f,
        -0.5f, 0.5f, 0.5f,  -1f, 0f, 0f,
        -0.5f, 0.5f,-0.5f,  -1f, 0f, 0f,
        // ── 右面 (法线朝 +X) ──
         0.5f,-0.5f, 0.5f,   1f, 0f, 0f,
         0.5f,-0.5f,-0.5f,   1f, 0f, 0f,
         0.5f, 0.5f,-0.5f,   1f, 0f, 0f,
         0.5f, 0.5f, 0.5f,   1f, 0f, 0f,
        // ── 顶面 (法线朝 +Y) ──
        -0.5f, 0.5f, 0.5f,   0f, 1f, 0f,
         0.5f, 0.5f, 0.5f,   0f, 1f, 0f,
         0.5f, 0.5f,-0.5f,   0f, 1f, 0f,
        -0.5f, 0.5f,-0.5f,   0f, 1f, 0f,
        // ── 底面 (法线朝 -Y) ──
        -0.5f,-0.5f,-0.5f,   0f,-1f, 0f,
         0.5f,-0.5f,-0.5f,   0f,-1f, 0f,
         0.5f,-0.5f, 0.5f,   0f,-1f, 0f,
        -0.5f,-0.5f, 0.5f,   0f,-1f, 0f,
    )

    /**
     * 正方体三角形索引 —— 告诉 GPU 如何把 24 个顶点连成 12 个三角形
     *
     * 每个面 4 个顶点 → 2 个三角形 → 6 个索引
     * 6 个面 × 6 = 36 个索引
     */
    private val cubeIndices = shortArrayOf(
         0,  1,  2,   0,  2,  3,    // 前
         4,  5,  6,   4,  6,  7,    // 后
         8,  9, 10,   8, 10, 11,    // 左
        12, 13, 14,  12, 14, 15,    // 右
        16, 17, 18,  16, 18, 19,    // 顶
        20, 21, 22,  20, 22, 23,    // 底
    )

    // ╔═══════════════════════════════════════════════════════════════╗
    // ║  第二步：编写 Shader（着色器）源码                              ║
    // ║                                                              ║
    // ║  Shader 是运行在 GPU 上的小程序，分两种：                       ║
    // ║    • 顶点着色器(Vertex Shader)：处理每个顶点的位置变换           ║
    // ║    • 片元着色器(Fragment Shader)：计算每个像素的最终颜色          ║
    // ║                                                              ║
    // ║  OpenGL ES 2.0 使用 GLSL ES 1.00 语法：                       ║
    // ║    输入变量用 attribute，传递变量用 varying，                    ║
    // ║    输出颜色写到 gl_FragColor，CubeMap采样用 textureCube()       ║
    // ╚═══════════════════════════════════════════════════════════════╝

    /**
     * 天空盒 · 顶点着色器
     *
     * 功能：把天空盒立方体的顶点变换到屏幕空间
     * 要点：
     *   • aPos 既是顶点位置，也是 CubeMap 的采样方向
     *   • uVP 是去掉平移的「视图-投影」矩阵，让天空盒像在无限远处
     *   • pos.xyww 技巧：令 z = w，透视除法后深度 = z/w = 1.0（最远）
     */
    private val skyboxVertexShader = """
        uniform mat4 uVP;
        attribute vec3 aPos;
        varying vec3 vDir;

        void main() {
            vDir = aPos;
            vec4 pos = uVP * vec4(aPos, 1.0);
            gl_Position = pos.xyww;
        }
    """.trimIndent()

    /**
     * 天空盒 · 片元着色器
     *
     * 功能：用方向向量从 CubeMap 采样天空颜色
     * 要点：
     *   • textureCube(采样器, 方向) —— GPU 自动判断该方向对应哪个面
     *   • 这就是为什么转头看不同方向会看到不同的天空
     */
    private val skyboxFragmentShader = """
        precision mediump float;
        uniform samplerCube uSkybox;
        varying vec3 vDir;

        void main() {
            gl_FragColor = textureCube(uSkybox, vDir);
        }
    """.trimIndent()

    /**
     * 反射正方体 · 顶点着色器
     *
     * 功能：将正方体顶点变换到屏幕空间，同时传递世界坐标和法线给片元着色器
     * 要点：
     *   • 模型矩阵为单位矩阵（正方体固定在原点），所以世界坐标 = 模型坐标
     *   • 法线用于计算反射方向
     */
    private val reflectVertexShader = """
        uniform mat4 uMVP;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        varying vec3 vWorldPos;
        varying vec3 vNormal;

        void main() {
            vWorldPos = aPos;
            vNormal = aNormal;
            gl_Position = uMVP * vec4(aPos, 1.0);
        }
    """.trimIndent()

    /**
     * 反射正方体 · 片元着色器
     *
     * 功能：计算环境反射颜色
     * 要点（环境反射三步）：
     *   ① I = normalize(表面点 - 摄像机位置) —— 入射方向
     *   ② R = reflect(I, 法线)               —— 反射方向
     *   ③ textureCube(CubeMap, R)             —— 用反射方向采样天空盒颜色
     *
     *   最后混合金属底色(35%) + 环境反射(65%)，模拟金属车漆效果
     */
    private val reflectFragmentShader = """
        precision mediump float;
        uniform samplerCube uSkybox;
        uniform vec3 uCamPos;
        varying vec3 vWorldPos;
        varying vec3 vNormal;

        void main() {
            vec3 I = normalize(vWorldPos - uCamPos);
            vec3 R = reflect(I, normalize(vNormal));
            vec4 env = textureCube(uSkybox, R);

            vec3 tint = vec3(0.72, 0.78, 0.85);
            gl_FragColor = vec4(mix(tint, env.rgb, 0.65), 1.0);
        }
    """.trimIndent()

    // ╔═══════════════════════════════════════════════════════════════╗
    // ║  运行时状态（在第三步 onSurfaceCreated 中初始化）               ║
    // ╚═══════════════════════════════════════════════════════════════╝

    // 两个 GPU 程序 ID（天空盒用一个，反射正方体用另一个）
    private var skyProgram = 0
    private var cubeProgram = 0

    // CubeMap 纹理 ID —— 天空盒和反射共用同一张
    private var cubemapTexture = 0

    // 天空盒 Shader 的 uniform/attribute 句柄
    private var hSkyVP = 0       // VP 矩阵
    private var hSkySampler = 0  // CubeMap 采样器
    private var hSkyPos = 0      // 顶点位置 attribute

    // 反射正方体 Shader 的 uniform/attribute 句柄
    private var hCubeMVP = 0     // MVP 矩阵
    private var hCubeSampler = 0 // CubeMap 采样器
    private var hCubeCamPos = 0  // 摄像机位置
    private var hCubePos = 0     // 顶点位置 attribute
    private var hCubeNormal = 0  // 顶点法线 attribute

    // 矩阵数组（4x4 矩阵 = 16 个 float）
    private val projMatrix  = FloatArray(16)  // 投影矩阵
    private val viewMatrix  = FloatArray(16)  // 视图矩阵
    private val mvpMatrix   = FloatArray(16)  // MVP 合成矩阵
    private val skyboxVP    = FloatArray(16)  // 天空盒专用 VP 矩阵
    private val tempMatrix  = FloatArray(16)  // 临时矩阵

    // GPU 缓冲区
    private lateinit var skyVertexBuffer: FloatBuffer
    private lateinit var cubeVertexBuffer: FloatBuffer
    private lateinit var cubeIndexBuffer: ShortBuffer

    // ╔═══════════════════════════════════════════════════════════════╗
    // ║  第三步：初始化 OpenGL 资源 (onSurfaceCreated)                 ║
    // ║                                                              ║
    // ║  这里完成所有一次性准备工作：                                    ║
    // ║    3a. 编译并链接 Shader 程序                                  ║
    // ║    3b. 获取 Shader 中变量的句柄                                ║
    // ║    3c. 生成 CubeMap 纹理（6 张渐变天空图）                      ║
    // ║    3d. 创建顶点/索引缓冲区                                     ║
    // ╚═══════════════════════════════════════════════════════════════╝

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 设置清屏颜色（纯黑背景，实际会被天空盒覆盖）
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // ─── 3a. 编译链接天空盒 Shader ───
        skyProgram = buildProgram(skyboxVertexShader, skyboxFragmentShader)

        // ─── 3b. 获取天空盒 Shader 变量句柄 ───
        // uniform = 从 CPU 传给 GPU 的「全局参数」
        // attribute = 每个顶点独有的输入数据
        hSkyVP      = GLES20.glGetUniformLocation(skyProgram, "uVP")
        hSkySampler = GLES20.glGetUniformLocation(skyProgram, "uSkybox")
        hSkyPos     = GLES20.glGetAttribLocation(skyProgram, "aPos")

        // ─── 3a. 编译链接反射正方体 Shader ───
        cubeProgram = buildProgram(reflectVertexShader, reflectFragmentShader)

        // ─── 3b. 获取反射正方体 Shader 变量句柄 ───
        hCubeMVP     = GLES20.glGetUniformLocation(cubeProgram, "uMVP")
        hCubeSampler = GLES20.glGetUniformLocation(cubeProgram, "uSkybox")
        hCubeCamPos  = GLES20.glGetUniformLocation(cubeProgram, "uCamPos")
        hCubePos     = GLES20.glGetAttribLocation(cubeProgram, "aPos")
        hCubeNormal  = GLES20.glGetAttribLocation(cubeProgram, "aNormal")

        // ─── 3c. 生成天空盒 CubeMap 纹理 ───
        // CubeMap = 6 张图拼成的 360° 环境贴图
        // 天空盒渲染和正方体反射都从这张图采样
        cubemapTexture = buildCubemapTexture()

        // ─── 3d. 创建顶点/索引缓冲区 ───
        // GPU 不能直接读取 Kotlin 数组，需要通过 NIO Buffer 传递
        // allocateDirect = 分配堆外内存（GPU 可直接访问）
        // nativeOrder = 使用设备的字节序（Android 通常是小端序）

        skyVertexBuffer = ByteBuffer
            .allocateDirect(skyboxVertices.size * 4)  // float = 4 字节
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(skyboxVertices)
            .apply { position(0) }  // 重置读取位置到开头

        cubeVertexBuffer = ByteBuffer
            .allocateDirect(cubeVertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(cubeVertices)
            .apply { position(0) }

        cubeIndexBuffer = ByteBuffer
            .allocateDirect(cubeIndices.size * 2)  // short = 2 字节
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(cubeIndices)
            .apply { position(0) }
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // ║  第四步：设置投影矩阵 (onSurfaceChanged)                       ║
    // ║                                                              ║
    // ║  投影矩阵(Projection Matrix) = 摄像机的「镜头」               ║
    // ║  把 3D 空间投影到 2D 屏幕上                                    ║
    // ║  这里用透视投影：近大远小，模拟人眼看世界的效果                    ║
    // ╚═══════════════════════════════════════════════════════════════╝

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        // perspectiveM: 视角45°, 近平面1, 远平面100
        Matrix.perspectiveM(projMatrix, 0, 45f, width.toFloat() / height, 1f, 100f)
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // ║  第五步：每帧渲染 (onDrawFrame) —— 每秒调用 60 次             ║
    // ║                                                              ║
    // ║  渲染顺序很重要：                                              ║
    // ║    5a. 计算摄像机轨道位置（球面坐标转直角坐标）                   ║
    // ║    5b. 第一遍 Draw：渲染天空盒背景（关闭深度测试）               ║
    // ║    5c. 第二遍 Draw：渲染反射正方体（开启深度测试）               ║
    // ╚═══════════════════════════════════════════════════════════════╝

    override fun onDrawFrame(gl: GL10?) {
        // 清除颜色缓冲和深度缓冲，准备绘制新一帧
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 自动缓慢旋转（让场景动起来）
        rotationY += 0.12f

        // ═══════════════════════════════════════════════════
        // 5a. 计算摄像机位置（球面坐标 → 直角坐标）
        // ═══════════════════════════════════════════════════
        //
        // 摄像机绕原点做轨道运动（就像你绕着展车走一圈）：
        //   rotationX 控制上下仰角，rotationY 控制左右方位
        //   cameraDistance 控制远近
        //
        // 球面坐标公式：
        //   x = r × sin(水平角) × cos(仰角)
        //   y = r × sin(仰角)
        //   z = r × cos(水平角) × cos(仰角)

        val radX = Math.toRadians(rotationX.toDouble())
        val radY = Math.toRadians(rotationY.toDouble())
        val dist = cameraDistance
        val eyeX = (dist * sin(radY) * cos(radX)).toFloat()
        val eyeY = (dist * sin(radX)).toFloat()
        val eyeZ = (dist * cos(radY) * cos(radX)).toFloat()

        // 视图矩阵(View Matrix)：摄像机在轨道位置，始终看向原点(0,0,0)
        // 参数：眼睛位置(eye), 目标点(center), 上方向(up)
        Matrix.setLookAtM(viewMatrix, 0,
            eyeX, eyeY, eyeZ,   // 摄像机位置
            0f, 0f, 0f,         // 看向原点
            0f, 1f, 0f          // 头顶朝上
        )

        // ═══════════════════════════════════════════════════
        // 5b. 第一遍 Draw：渲染天空盒（背景）
        // ═══════════════════════════════════════════════════
        //
        // 关键技巧：
        //   • 去掉视图矩阵的平移分量，只保留旋转
        //     这样摄像机无论在哪，天空盒都像在「无限远处」
        //   • 关闭深度测试，让天空盒始终在最远处
        //   • Shader 中 gl_Position = pos.xyww 确保深度值为 1.0

        // 复制视图矩阵并清除平移部分（矩阵第4列的前3个元素）
        val skyView = FloatArray(16)
        System.arraycopy(viewMatrix, 0, skyView, 0, 16)
        skyView[12] = 0f  // 清除 X 平移
        skyView[13] = 0f  // 清除 Y 平移
        skyView[14] = 0f  // 清除 Z 平移

        // 天空盒 VP = Projection × SkyView（无平移）
        Matrix.multiplyMM(skyboxVP, 0, projMatrix, 0, skyView, 0)

        // 关闭深度测试 → 天空盒不会遮挡后续绘制的正方体
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)

        // 激活天空盒 Shader 程序
        GLES20.glUseProgram(skyProgram)

        // 传递 VP 矩阵到 GPU
        GLES20.glUniformMatrix4fv(hSkyVP, 1, false, skyboxVP, 0)

        // 绑定 CubeMap 纹理到纹理单元 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, cubemapTexture)
        GLES20.glUniform1i(hSkySampler, 0)  // 告诉 Shader 从纹理单元 0 采样

        // 绑定天空盒顶点数据 → attribute aPos
        skyVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(hSkyPos, 3, GLES20.GL_FLOAT, false, 0, skyVertexBuffer)
        GLES20.glEnableVertexAttribArray(hSkyPos)

        // Draw Call：画 36 个顶点（12 个三角形）组成的天空盒
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36)

        GLES20.glDisableVertexAttribArray(hSkyPos)

        // ═══════════════════════════════════════════════════
        // 5c. 第二遍 Draw：渲染反射正方体
        // ═══════════════════════════════════════════════════
        //
        // 正方体放在原点不动，通过摄像机环绕来改变视角。
        // 反射效果：Shader 用反射向量从同一张 CubeMap 采样，
        //           所以正方体表面看起来「倒映」了周围天空。

        // 重新开启深度测试 → 正方体会正确遮挡
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        // MVP = Projection × View（模型矩阵为单位矩阵，正方体在原点）
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        // 激活反射正方体 Shader 程序
        GLES20.glUseProgram(cubeProgram)

        // 传递 uniform 参数
        GLES20.glUniformMatrix4fv(hCubeMVP, 1, false, mvpMatrix, 0)
        GLES20.glUniform3f(hCubeCamPos, eyeX, eyeY, eyeZ)  // 摄像机位置（算反射方向用）

        // 绑定 CubeMap 纹理（复用天空盒同一张 CubeMap）
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, cubemapTexture)
        GLES20.glUniform1i(hCubeSampler, 0)

        // 绑定正方体顶点数据 → attribute aPos + aNormal
        // 交错布局: [pos.x, pos.y, pos.z, norm.x, norm.y, norm.z, ...]
        // stride = 6 × 4字节 = 24字节（每个顶点占 24 字节）
        val stride = 6 * 4

        // 位置属性：从偏移 0 开始读取，每 stride 字节读 3 个 float
        cubeVertexBuffer.position(0)
        GLES20.glVertexAttribPointer(hCubePos, 3, GLES20.GL_FLOAT, false, stride, cubeVertexBuffer)
        GLES20.glEnableVertexAttribArray(hCubePos)

        // 法线属性：从偏移 3 开始读取（跳过前 3 个 float 的位置数据）
        cubeVertexBuffer.position(3)
        GLES20.glVertexAttribPointer(hCubeNormal, 3, GLES20.GL_FLOAT, false, stride, cubeVertexBuffer)
        GLES20.glEnableVertexAttribArray(hCubeNormal)

        // 索引绘制：用 36 个索引画出 12 个三角形
        GLES20.glDrawElements(
            GLES20.GL_TRIANGLES, 36, GLES20.GL_UNSIGNED_SHORT, cubeIndexBuffer
        )

        GLES20.glDisableVertexAttribArray(hCubePos)
        GLES20.glDisableVertexAttribArray(hCubeNormal)
    }

    // ╔═══════════════════════════════════════════════════════════════╗
    // ║  内部工具方法                                                  ║
    // ║                                                              ║
    // ║  以下方法都是上面步骤中调用的具体实现：                           ║
    // ║    • buildProgram()         —— 编译链接 Shader 程序            ║
    // ║    • compileShader()        —— 编译单个着色器                  ║
    // ║    • buildCubemapTexture()  —— 生成天空盒 CubeMap 纹理         ║
    // ║    • createSkyFaceBitmap()  —— 生成单个面的渐变天空图           ║
    // ╚═══════════════════════════════════════════════════════════════╝

    /**
     * 编译并链接一个 Shader 程序
     *
     * GPU 程序 = 顶点着色器 + 片元着色器，链接后才能使用。
     * 流程：编译 VS → 编译 FS → 创建程序 → 附着两个 Shader → 链接 → 检查错误
     */
    private fun buildProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vertSrc)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragSrc)
        if (vs == 0 || fs == 0) return 0

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)

        // 检查链接是否成功
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(program)}")
            GLES20.glDeleteProgram(program)
            return 0
        }

        // 链接完成，Shader 对象可以释放（程序已包含编译后的代码）
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    /**
     * 编译单个着色器
     *
     * @param type   GLES20.GL_VERTEX_SHADER 或 GLES20.GL_FRAGMENT_SHADER
     * @param source GLSL 源码字符串
     * @return Shader ID，失败返回 0
     */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)

        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val label = if (type == GLES20.GL_VERTEX_SHADER) "Vertex" else "Fragment"
            Log.e(TAG, "$label shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    /**
     * 生成天空盒 CubeMap 纹理
     *
     * CubeMap(立方体贴图) = 6 张图拼成一个正方体，模拟 360° 环境。
     * 6 个面的 OpenGL 常量有固定顺序：+X, -X, +Y, -Y, +Z, -Z
     *
     * 这里用代码动态生成日落风格的渐变天空（实际项目中通常加载 6 张图片）：
     *   +Y(顶面) = 深蓝夜空
     *   -Y(底面) = 暗色地面
     *   四个侧面 = 深蓝→暖橙的日落渐变
     *
     * @return CubeMap 纹理 ID
     */
    private fun buildCubemapTexture(): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_CUBE_MAP, texIds[0])

        // CubeMap 的 6 个面（顺序固定，不能改）
        val faces = intArrayOf(
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_X,  // 右
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,  // 左
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_Y,  // 上
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,  // 下
            GLES20.GL_TEXTURE_CUBE_MAP_POSITIVE_Z,  // 后
            GLES20.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z,  // 前
        )

        // 为每个面生成渐变天空图并上传到 GPU 显存
        for ((index, face) in faces.withIndex()) {
            val bitmap = createSkyFaceBitmap(index)
            GLUtils.texImage2D(face, 0, bitmap, 0)
            bitmap.recycle()  // 已上传到 GPU，释放 CPU 内存
        }

        // 设置纹理采样参数
        // LINEAR = 线性插值（平滑过渡，避免像素锯齿）
        // CLAMP_TO_EDGE = 边缘钳制（防止面与面之间出现缝隙）
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_CUBE_MAP, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        return texIds[0]
    }

    /**
     * 生成天空盒单个面的渐变 Bitmap
     *
     * 用 Android Canvas 绘制渐变色填充，模拟日落天空效果。
     * 实际项目中这一步会替换为 BitmapFactory.decodeResource() 加载真实图片。
     *
     * @param faceIndex 面索引：0=+X(右), 1=-X(左), 2=+Y(顶), 3=-Y(底), 4=+Z(后), 5=-Z(前)
     */
    private fun createSkyFaceBitmap(faceIndex: Int): Bitmap {
        val size = 256
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        val s = size.toFloat()

        when (faceIndex) {
            2 -> {
                // +Y 顶面 → 深蓝色夜空
                paint.shader = LinearGradient(
                    0f, 0f, 0f, s,
                    Color.rgb(8, 8, 35), Color.rgb(25, 25, 70),
                    Shader.TileMode.CLAMP
                )
            }
            3 -> {
                // -Y 底面 → 暗色地面
                paint.shader = LinearGradient(
                    0f, 0f, 0f, s,
                    Color.rgb(45, 38, 30), Color.rgb(22, 18, 14),
                    Shader.TileMode.CLAMP
                )
            }
            else -> {
                // 四个侧面 → 日落渐变（深蓝 → 蓝紫 → 暖橙 → 金橙）
                paint.shader = LinearGradient(
                    0f, 0f, 0f, s,
                    intArrayOf(
                        Color.rgb(12, 12, 55),    // 天顶：深蓝
                        Color.rgb(35, 45, 95),    // 高空：蓝紫
                        Color.rgb(170, 110, 55),  // 地平线：暖橙
                        Color.rgb(210, 130, 45),  // 近地面：金橙
                    ),
                    floatArrayOf(0f, 0.4f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }

        canvas.drawRect(0f, 0f, s, s, paint)
        return bitmap
    }

    companion object {
        private const val TAG = "SkyboxES20"
    }
}
