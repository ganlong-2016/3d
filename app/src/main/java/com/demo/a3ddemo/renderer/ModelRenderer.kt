package com.demo.a3ddemo.renderer

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.demo.a3ddemo.gl.DemoRenderer
import com.demo.a3ddemo.gl.ObjLoader
import com.demo.a3ddemo.gl.ShaderUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Demo 5：3D 模型加载渲染器
 *
 * 对应文档：
 *   「Mesh（网格）— 白车身，用三角形拼出物体的形状」
 *   「面数越多越精细，但 GPU 计算量越大」
 *
 * ─── 核心要点 ───
 *
 *   无论模型来自 .obj 文件、.glTF 还是程序化生成，
 *   最终都归结为同一套渲染管线：
 *
 *     顶点数据(位置+法线)
 *       → Vertex Shader (MVP 变换)
 *       → 光栅化
 *       → Fragment Shader (光照计算)
 *       → 屏幕像素
 *
 *   这里复用了 Demo 3 的 Blinn-Phong 光照 Shader (lit_cube.vert / lit_cube.frag)，
 *   证明同一个「喷漆机器人」可以给任何形状的「白车身」上色——
 *   不管是正方体还是环形体，渲染流程完全一样。
 *
 * ─── 数据来源 ───
 *
 *   优先从 assets/models/sample.obj 加载（演示 OBJ 解析流程），
 *   如果加载失败则使用程序化生成的环形体 (Torus)。
 *   两种方式最终产出相同的 MeshData (positions + normals + indices)，
 *   后续渲染完全一致。
 */
class ModelRenderer(private val context: Context) : DemoRenderer {

    @Volatile override var rotationX = -20f
    @Volatile override var rotationY = 30f
    @Volatile override var cameraDistance = 2.8f

    private var program = 0

    // Uniform 句柄 — 与 LitCubeRenderer 完全一致
    private var hMVP = 0
    private var hModel = 0
    private var hLightDir = 0
    private var hLightColor = 0
    private var hAmbient = 0
    private var hObjColor = 0
    private var hCamPos = 0
    private var hShininess = 0

    private val projMatrix  = FloatArray(16)
    private val viewMatrix  = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix   = FloatArray(16)
    private val temp        = FloatArray(16)

    private lateinit var vertexBuf: FloatBuffer
    private lateinit var indexBuf: ShortBuffer
    private var indexCount = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.06f, 0.06f, 0.10f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // ─── 复用 Demo 3 的光照 Shader ───
        // 同一个 Shader 程序能渲染任何 Mesh，这就是 GPU 管线的通用性
        program = ShaderUtils.createProgram(
            context, "shaders/lit_cube.vert", "shaders/lit_cube.frag"
        )
        hMVP        = GLES30.glGetUniformLocation(program, "uMVP")
        hModel      = GLES30.glGetUniformLocation(program, "uModel")
        hLightDir   = GLES30.glGetUniformLocation(program, "uLightDir")
        hLightColor = GLES30.glGetUniformLocation(program, "uLightColor")
        hAmbient    = GLES30.glGetUniformLocation(program, "uAmbient")
        hObjColor   = GLES30.glGetUniformLocation(program, "uObjColor")
        hCamPos     = GLES30.glGetUniformLocation(program, "uCamPos")
        hShininess  = GLES30.glGetUniformLocation(program, "uShininess")

        // ─── 加载模型数据 ───
        // 优先从 OBJ 文件加载，失败则用程序化生成的 Torus
        // 无论哪种方式，最终都是 MeshData = positions + normals + indices
        val mesh = try {
            ObjLoader.loadObj(context, "models/sample.obj")
        } catch (_: Exception) {
            ObjLoader.generateTorus()
        }

        indexCount = mesh.indices.size

        // 将 positions 和 normals 交错排列到同一个缓冲区
        // 布局: [pos.x, pos.y, pos.z, norm.x, norm.y, norm.z, ...]
        // 这与 Demo 3 的 LitCubeRenderer 使用完全相同的数据布局
        val interleaved = FloatArray(mesh.vertexCount * 6)
        for (i in 0 until mesh.vertexCount) {
            interleaved[i * 6 + 0] = mesh.positions[i * 3 + 0]
            interleaved[i * 6 + 1] = mesh.positions[i * 3 + 1]
            interleaved[i * 6 + 2] = mesh.positions[i * 3 + 2]
            interleaved[i * 6 + 3] = mesh.normals[i * 3 + 0]
            interleaved[i * 6 + 4] = mesh.normals[i * 3 + 1]
            interleaved[i * 6 + 5] = mesh.normals[i * 3 + 2]
        }

        vertexBuf = ByteBuffer.allocateDirect(interleaved.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(interleaved).apply { position(0) }

        indexBuf = ByteBuffer.allocateDirect(mesh.indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(mesh.indices).apply { position(0) }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        Matrix.perspectiveM(projMatrix, 0, 45f, w.toFloat() / h, 1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        rotationY += 0.2f

        // ─── MVP 矩阵 — 与前几个 Demo 完全一致 ───
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.multiplyMM(temp, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, temp, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(hMVP, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(hModel, 1, false, modelMatrix, 0)

        // ─── 灯光和材质参数 ───
        GLES30.glUniform3f(hLightDir, 0.48f, 0.72f, 0.50f)
        GLES30.glUniform3f(hLightColor, 1.0f, 0.95f, 0.88f)
        GLES30.glUniform3f(hAmbient, 0.10f, 0.10f, 0.15f)
        GLES30.glUniform3f(hObjColor, 0.50f, 0.72f, 0.85f)   // 天蓝色调
        GLES30.glUniform3f(hCamPos, 0f, 0f, cameraDistance)
        GLES30.glUniform1f(hShininess, 48f)

        // ─── 绑定顶点属性 ───
        // 交错布局: position(3 floats) + normal(3 floats) = 24 bytes/vertex
        val stride = 6 * 4
        vertexBuf.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, vertexBuf)
        GLES30.glEnableVertexAttribArray(0)

        vertexBuf.position(3)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, vertexBuf)
        GLES30.glEnableVertexAttribArray(1)

        // ─── Draw Call ───
        // 这里和 Demo 3 用的是同一种绘制调用，
        // 只是索引数量不同（正方体 36 个索引 vs Torus 几千个索引）
        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, indexBuf
        )

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }
}
