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
 * Demo 3：带光照的正方体
 *
 * 对应文档章节：
 *   「二、Shader — 真正干活的喷漆机器人」
 *   「三、灯光(Light) — 展厅里的灯」
 *   「二、材质(Material) — 一套完整的表面工艺方案」
 *
 * 展示的核心概念：
 *   - 灯光(Light)：平行光模拟太阳/展厅射灯，从右上方照射
 *   - 材质(Material)：物体底色 + 高光锐度，决定「车漆工艺」
 *   - Shader：片元着色器实现 Blinn-Phong 光照模型，逐像素计算颜色
 *   - 法线(Normal)：表面朝向，决定光照的明暗分布
 *
 * 光照三分量：环境光(基础照明) + 漫反射(明暗过渡) + 镜面反射(高光)
 */
class LitCubeRenderer(private val context: Context) : DemoRenderer {

    @Volatile override var rotationX = -25f
    @Volatile override var rotationY = 45f
    @Volatile override var cameraDistance = 3.5f

    private var program = 0

    // Uniform 句柄 — 用于从 CPU 向 GPU 传递参数
    private var hMVP = 0        // MVP 矩阵
    private var hModel = 0      // 模型矩阵（用于变换法线）
    private var hLightDir = 0   // 光照方向
    private var hLightColor = 0 // 光的颜色
    private var hAmbient = 0    // 环境光
    private var hObjColor = 0   // 物体底色
    private var hCamPos = 0     // 摄像机位置（用于镜面反射）
    private var hShininess = 0  // 高光锐利度

    private val projMatrix  = FloatArray(16)
    private val viewMatrix  = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix   = FloatArray(16)
    private val temp        = FloatArray(16)

    private lateinit var vertexBuf: FloatBuffer
    private lateinit var indexBuf: ShortBuffer

    // 24 顶点 = 位置(3) + 法线(3) = 6 floats/顶点
    // 法线(Normal)：表面的朝向向量，光照计算的核心输入
    // 文档：「假装表面有凹凸 — 法线决定光线如何被反射」
    private val vertices = floatArrayOf(
        //       x     y     z    nx   ny   nz
        // 前面 — 法线朝 +Z
        -0.5f, -0.5f,  0.5f,  0f,  0f,  1f,
         0.5f, -0.5f,  0.5f,  0f,  0f,  1f,
         0.5f,  0.5f,  0.5f,  0f,  0f,  1f,
        -0.5f,  0.5f,  0.5f,  0f,  0f,  1f,
        // 后面 — 法线朝 -Z
         0.5f, -0.5f, -0.5f,  0f,  0f, -1f,
        -0.5f, -0.5f, -0.5f,  0f,  0f, -1f,
        -0.5f,  0.5f, -0.5f,  0f,  0f, -1f,
         0.5f,  0.5f, -0.5f,  0f,  0f, -1f,
        // 左面 — 法线朝 -X
        -0.5f, -0.5f, -0.5f, -1f,  0f,  0f,
        -0.5f, -0.5f,  0.5f, -1f,  0f,  0f,
        -0.5f,  0.5f,  0.5f, -1f,  0f,  0f,
        -0.5f,  0.5f, -0.5f, -1f,  0f,  0f,
        // 右面 — 法线朝 +X
         0.5f, -0.5f,  0.5f,  1f,  0f,  0f,
         0.5f, -0.5f, -0.5f,  1f,  0f,  0f,
         0.5f,  0.5f, -0.5f,  1f,  0f,  0f,
         0.5f,  0.5f,  0.5f,  1f,  0f,  0f,
        // 顶面 — 法线朝 +Y
        -0.5f,  0.5f,  0.5f,  0f,  1f,  0f,
         0.5f,  0.5f,  0.5f,  0f,  1f,  0f,
         0.5f,  0.5f, -0.5f,  0f,  1f,  0f,
        -0.5f,  0.5f, -0.5f,  0f,  1f,  0f,
        // 底面 — 法线朝 -Y
        -0.5f, -0.5f, -0.5f,  0f, -1f,  0f,
         0.5f, -0.5f, -0.5f,  0f, -1f,  0f,
         0.5f, -0.5f,  0.5f,  0f, -1f,  0f,
        -0.5f, -0.5f,  0.5f,  0f, -1f,  0f,
    )

    private val indices = shortArrayOf(
         0,  1,  2,   0,  2,  3,
         4,  5,  6,   4,  6,  7,
         8,  9, 10,   8, 10, 11,
        12, 13, 14,  12, 14, 15,
        16, 17, 18,  16, 18, 19,
        20, 21, 22,  20, 22, 23,
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.08f, 0.08f, 0.12f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // 加载光照 Shader — Blinn-Phong 光照模型
        program = ShaderUtils.createProgram(
            context, "shaders/lit_cube.vert", "shaders/lit_cube.frag"
        )

        // 获取所有 uniform 句柄
        hMVP        = GLES30.glGetUniformLocation(program, "uMVP")
        hModel      = GLES30.glGetUniformLocation(program, "uModel")
        hLightDir   = GLES30.glGetUniformLocation(program, "uLightDir")
        hLightColor = GLES30.glGetUniformLocation(program, "uLightColor")
        hAmbient    = GLES30.glGetUniformLocation(program, "uAmbient")
        hObjColor   = GLES30.glGetUniformLocation(program, "uObjColor")
        hCamPos     = GLES30.glGetUniformLocation(program, "uCamPos")
        hShininess  = GLES30.glGetUniformLocation(program, "uShininess")

        vertexBuf = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(vertices).apply { position(0) }

        indexBuf = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(indices).apply { position(0) }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        Matrix.perspectiveM(projMatrix, 0, 45f, w.toFloat() / h, 1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        rotationY += 0.15f

        // ─── 构建 MVP 矩阵 ───
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.multiplyMM(temp, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, temp, 0)

        GLES30.glUseProgram(program)

        // 传递矩阵
        GLES30.glUniformMatrix4fv(hMVP, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(hModel, 1, false, modelMatrix, 0)

        // ─── 设置灯光参数 ───
        // 文档：「平行光(Directional Light) — 模拟户外太阳光」
        GLES30.glUniform3f(hLightDir, 0.48f, 0.72f, 0.50f)      // 右上方光照方向
        GLES30.glUniform3f(hLightColor, 1.0f, 0.95f, 0.88f)     // 暖白色灯光

        // ─── 设置材质参数 ───
        // 文档：「材质 — 规定了用什么颜色、高光还是哑光」
        GLES30.glUniform3f(hAmbient, 0.12f, 0.12f, 0.18f)       // 环境光（冷色基底）
        GLES30.glUniform3f(hObjColor, 0.85f, 0.45f, 0.35f)      // 物体底色（珊瑚红）
        GLES30.glUniform3f(hCamPos, 0f, 0f, cameraDistance)      // 摄像机位置
        GLES30.glUniform1f(hShininess, 32f)                      // 高光锐利度

        // 绑定顶点属性：位置 + 法线
        val stride = 6 * 4
        vertexBuf.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, vertexBuf)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuf.position(3)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, vertexBuf)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawElements(GLES30.GL_TRIANGLES, 36, GLES30.GL_UNSIGNED_SHORT, indexBuf)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }
}
