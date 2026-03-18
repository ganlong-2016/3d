package com.demo.a3ddemo.renderer

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.demo.a3ddemo.gl.DemoRenderer
import com.demo.a3ddemo.gl.ShaderUtils
import com.demo.a3ddemo.gl.TextureHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Demo 2：纹理贴图正方体
 *
 * 对应文档章节：
 *   「二、给白车身穿上外衣 — 纹理」
 *   「纹理怎么贴到模型上？— UV 映射」
 *
 * 展示的核心概念：
 *   - 纹理(Texture)：一张 2D 图片，就像「漆料 / 皮革样本」
 *   - UV 映射：模型上每个顶点记录 (u, v) 坐标，指定它对应纹理图上的位置
 *   - 纹理采样：片元着色器用 texture() 函数根据 UV 从纹理取色
 *
 * 棋盘格纹理的蓝色边框和红色十字线帮助直观理解 UV 映射关系
 */
class TexturedCubeRenderer(private val context: Context) : DemoRenderer {

    @Volatile override var rotationX = -25f
    @Volatile override var rotationY = 45f
    @Volatile override var cameraDistance = 3.5f

    private var program = 0
    private var mvpHandle = 0
    private var texHandle = 0
    private var textureId = 0   // 纹理对象 ID

    private val projMatrix  = FloatArray(16)
    private val viewMatrix  = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix   = FloatArray(16)
    private val temp        = FloatArray(16)

    private lateinit var vertexBuf: FloatBuffer
    private lateinit var indexBuf: ShortBuffer

    // 24 个顶点 = 位置(3) + UV(2) = 5 floats/顶点
    // UV 坐标范围 [0, 1]：(0,0) 到 (1,1) 覆盖完整纹理
    // 文档：「模型上每个顶点记录一对 (u, v) 坐标」
    private val vertices = floatArrayOf(
        //       x     y     z    u   v
        // 前面
        -0.5f, -0.5f,  0.5f, 0f, 0f,
         0.5f, -0.5f,  0.5f, 1f, 0f,
         0.5f,  0.5f,  0.5f, 1f, 1f,
        -0.5f,  0.5f,  0.5f, 0f, 1f,
        // 后面
         0.5f, -0.5f, -0.5f, 0f, 0f,
        -0.5f, -0.5f, -0.5f, 1f, 0f,
        -0.5f,  0.5f, -0.5f, 1f, 1f,
         0.5f,  0.5f, -0.5f, 0f, 1f,
        // 左面
        -0.5f, -0.5f, -0.5f, 0f, 0f,
        -0.5f, -0.5f,  0.5f, 1f, 0f,
        -0.5f,  0.5f,  0.5f, 1f, 1f,
        -0.5f,  0.5f, -0.5f, 0f, 1f,
        // 右面
         0.5f, -0.5f,  0.5f, 0f, 0f,
         0.5f, -0.5f, -0.5f, 1f, 0f,
         0.5f,  0.5f, -0.5f, 1f, 1f,
         0.5f,  0.5f,  0.5f, 0f, 1f,
        // 顶面
        -0.5f,  0.5f,  0.5f, 0f, 0f,
         0.5f,  0.5f,  0.5f, 1f, 0f,
         0.5f,  0.5f, -0.5f, 1f, 1f,
        -0.5f,  0.5f, -0.5f, 0f, 1f,
        // 底面
        -0.5f, -0.5f, -0.5f, 0f, 0f,
         0.5f, -0.5f, -0.5f, 1f, 0f,
         0.5f, -0.5f,  0.5f, 1f, 1f,
        -0.5f, -0.5f,  0.5f, 0f, 1f,
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
        GLES30.glClearColor(0.11f, 0.11f, 0.14f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // 从 assets 加载纹理专用 Shader
        program = ShaderUtils.createProgram(
            context, "shaders/textured_cube.vert", "shaders/textured_cube.frag"
        )
        mvpHandle = GLES30.glGetUniformLocation(program, "uMVP")
        texHandle = GLES30.glGetUniformLocation(program, "uTexture")

        // 生成棋盘格纹理并上传到 GPU
        // 文档：「纹理 — 漆料/皮革纹样等图片素材」
        textureId = TextureHelper.createCheckerboardTexture()

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
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        // 绑定纹理到纹理单元 0
        // GPU 的片元着色器通过 uTexture 采样器读取这张纹理
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(texHandle, 0)  // 告诉采样器使用纹理单元 0

        // 绑定顶点属性：位置 + UV
        val stride = 5 * 4
        vertexBuf.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, vertexBuf)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuf.position(3)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, vertexBuf)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawElements(GLES30.GL_TRIANGLES, 36, GLES30.GL_UNSIGNED_SHORT, indexBuf)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }
}
