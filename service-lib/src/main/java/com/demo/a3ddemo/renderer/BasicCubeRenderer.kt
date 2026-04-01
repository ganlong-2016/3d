package com.demo.a3ddemo.renderer

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.demo.a3ddemo.gl.DemoRenderer
import com.demo.a3ddemo.gl.ShaderUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Demo 1：基础彩色正方体
 *
 * 对应文档章节：
 *   「一、3D 模型的本质 — 用三角形拼出一辆车」
 *   「四、一辆 3D 车从数据到中控屏的完整旅程」
 *
 * 展示的核心概念：
 *   - 顶点(Vertex)：空间中的点，有 (x, y, z) 坐标，像车身上的焊接点
 *   - 三角形(Triangle)：三个顶点连成一片，是 GPU 处理的最小面单元
 *   - 网格(Mesh)：12 个三角形拼成 6 个面 = 一个完整的正方体（「白车身」）
 *   - MVP 变换：模型空间→世界空间→摄像机空间→屏幕 的坐标变换
 *
 * 6 个面使用不同颜色，方便观察三角形如何拼接出立方体
 */
class BasicCubeRenderer(private val context: Context) : DemoRenderer {

    // ─── 触控交互状态（由 UI 线程写入，GL 线程读取）───
    @Volatile override var rotationX = -25f       // 上下旋转角
    @Volatile override var rotationY = 45f        // 左右旋转角
    @Volatile override var cameraDistance = 3.5f   // 摄像机距离（双指缩放）

    private var program = 0    // GPU 程序 ID
    private var mvpHandle = 0  // MVP 矩阵的 uniform 句柄

    // ─── MVP 矩阵组 ───
    // 文档：「这三步用三个矩阵完成，合称 MVP 变换」
    private val projMatrix  = FloatArray(16)  // P: 投影矩阵 — 3D→2D，产生近大远小效果
    private val viewMatrix  = FloatArray(16)  // V: 视图矩阵 — 摄像机的位置和朝向
    private val modelMatrix = FloatArray(16)  // M: 模型矩阵 — 物体自身的位置、旋转、缩放
    private val mvpMatrix   = FloatArray(16)  // 最终结果 MVP = P × V × M
    private val temp        = FloatArray(16)  // 中间计算缓冲

    private lateinit var vertexBuf: FloatBuffer
    private lateinit var indexBuf: ShortBuffer

    // ─── 顶点数据 ───
    // 24 个顶点 = 6 面 × 4 顶点（每个面需独立顶点以支持独立颜色）
    // 每个顶点 = 位置(3 floats) + 颜色(4 floats) = 7 floats
    // 文档：「顶点(Vertex) — 空间中的一个点，有 x、y、z 三个坐标」
    private val vertices = floatArrayOf(
        //       x     y     z      r     g     b     a
        // 前面 — 红色（法线朝 +Z）
        -0.5f, -0.5f,  0.5f,  0.90f, 0.22f, 0.22f, 1f,
         0.5f, -0.5f,  0.5f,  0.90f, 0.22f, 0.22f, 1f,
         0.5f,  0.5f,  0.5f,  0.90f, 0.22f, 0.22f, 1f,
        -0.5f,  0.5f,  0.5f,  0.90f, 0.22f, 0.22f, 1f,
        // 后面 — 绿色（法线朝 -Z）
         0.5f, -0.5f, -0.5f,  0.22f, 0.78f, 0.22f, 1f,
        -0.5f, -0.5f, -0.5f,  0.22f, 0.78f, 0.22f, 1f,
        -0.5f,  0.5f, -0.5f,  0.22f, 0.78f, 0.22f, 1f,
         0.5f,  0.5f, -0.5f,  0.22f, 0.78f, 0.22f, 1f,
        // 左面 — 蓝色（法线朝 -X）
        -0.5f, -0.5f, -0.5f,  0.22f, 0.30f, 0.90f, 1f,
        -0.5f, -0.5f,  0.5f,  0.22f, 0.30f, 0.90f, 1f,
        -0.5f,  0.5f,  0.5f,  0.22f, 0.30f, 0.90f, 1f,
        -0.5f,  0.5f, -0.5f,  0.22f, 0.30f, 0.90f, 1f,
        // 右面 — 黄色（法线朝 +X）
         0.5f, -0.5f,  0.5f,  0.95f, 0.85f, 0.15f, 1f,
         0.5f, -0.5f, -0.5f,  0.95f, 0.85f, 0.15f, 1f,
         0.5f,  0.5f, -0.5f,  0.95f, 0.85f, 0.15f, 1f,
         0.5f,  0.5f,  0.5f,  0.95f, 0.85f, 0.15f, 1f,
        // 顶面 — 青色（法线朝 +Y）
        -0.5f,  0.5f,  0.5f,  0.22f, 0.85f, 0.85f, 1f,
         0.5f,  0.5f,  0.5f,  0.22f, 0.85f, 0.85f, 1f,
         0.5f,  0.5f, -0.5f,  0.22f, 0.85f, 0.85f, 1f,
        -0.5f,  0.5f, -0.5f,  0.22f, 0.85f, 0.85f, 1f,
        // 底面 — 洋红（法线朝 -Y）
        -0.5f, -0.5f, -0.5f,  0.85f, 0.22f, 0.85f, 1f,
         0.5f, -0.5f, -0.5f,  0.85f, 0.22f, 0.85f, 1f,
         0.5f, -0.5f,  0.5f,  0.85f, 0.22f, 0.85f, 1f,
        -0.5f, -0.5f,  0.5f,  0.85f, 0.22f, 0.85f, 1f,
    )

    // 索引数组 — 定义三角形的顶点连接方式
    // 每 3 个索引 = 1 个三角形，共 12 个三角形拼成 6 个面
    // 文档：「三角形 — 三个焊接点连起来，形成一块最小的钢板」
    private val indices = shortArrayOf(
         0,  1,  2,   0,  2,  3,   // 前面（2 个三角形）
         4,  5,  6,   4,  6,  7,   // 后面
         8,  9, 10,   8, 10, 11,   // 左面
        12, 13, 14,  12, 14, 15,   // 右面
        16, 17, 18,  16, 18, 19,   // 顶面
        20, 21, 22,  20, 22, 23,   // 底面
    )

    // ═══ GL 生命周期回调 ═══

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // ─── GPU 环境初始化 ───
        GLES30.glClearColor(0.11f, 0.11f, 0.14f, 1f)  // 深灰背景
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)           // 开启深度测试
        // 文档：「深度缓冲(Z-Buffer) — 近的覆盖远的，车门挡住座椅」

        // 从 assets 加载 GLSL 文件并编译链接
        // 文档：「Shader 是一小段运行在 GPU 上的程序」
        program = ShaderUtils.createProgram(
            context, "shaders/basic_cube.vert", "shaders/basic_cube.frag"
        )
        mvpHandle = GLES30.glGetUniformLocation(program, "uMVP")

        // 将顶点数据上传到 native 直接内存（GPU 可直接读取）
        // 文档：「CPU 给 GPU 下达指令 — Draw Call」需要先准备好数据
        vertexBuf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(vertices).apply { position(0) }

        indexBuf = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(indices).apply { position(0) }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        // 设置透视投影矩阵
        // 文档：「透视投影 — 近大远小，有纵深感」
        Matrix.perspectiveM(projMatrix, 0, 45f, w.toFloat() / h, 1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        // ═══ 每帧渲染流程（对应文档第四章「完整旅程」）═══

        // 清屏：清除颜色缓冲和深度缓冲，准备画新一帧
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // 自动缓慢旋转（让静态画面有动感）
        rotationY += 0.15f

        // ─── 第一步：CPU 备料排产（构建变换矩阵）───
        // 文档：「CPU（总工程师）：摄像机在哪？灯光怎么打？」

        // V 矩阵：摄像机位于 (0, 0, distance)，看向原点
        Matrix.setLookAtM(viewMatrix, 0,
            0f, 0f, cameraDistance,  // 摄像机位置（双指缩放控制距离）
            0f, 0f, 0f,             // 看向原点
            0f, 1f, 0f)             // 上方向

        // M 矩阵：模型旋转（由触控拖动角度决定）
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)  // 绕 X 轴旋转
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)  // 绕 Y 轴旋转

        // ─── 第二步 & 第三步：MVP 矩阵计算 ───
        // 文档：「模型空间→世界空间→摄像机空间→屏幕」
        Matrix.multiplyMM(temp, 0, viewMatrix, 0, modelMatrix, 0)       // V × M
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, temp, 0)         // P × V × M

        // ─── 第四~七步：发送 Draw Call，触发 GPU 渲染流水线 ───
        // GPU 流水线：顶点着色器→光栅化→片元着色器→深度测试→帧缓冲→屏幕

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        // 绑定顶点属性 0 = 位置 (vec3)
        val stride = 7 * 4  // 每顶点 7 个 float × 4 字节 = 28 字节步长
        vertexBuf.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, vertexBuf)
        GLES30.glEnableVertexAttribArray(0)

        // 绑定顶点属性 1 = 颜色 (vec4)，从第 3 个 float 开始
        vertexBuf.position(3)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, stride, vertexBuf)
        GLES30.glEnableVertexAttribArray(1)

        // Draw Call — CPU 给 GPU 下达的「工单」
        // 文档：「Draw Call 就像总工程师给流水线下的工单」
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, 36, GLES30.GL_UNSIGNED_SHORT, indexBuf)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }
}
