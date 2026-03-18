package com.demo.a3ddemo.renderer

import android.content.Context
import android.opengl.GLES30
import android.opengl.Matrix
import com.demo.a3ddemo.gl.DemoRenderer
import com.demo.a3ddemo.gl.MeshData
import com.demo.a3ddemo.gl.ObjLoader
import com.demo.a3ddemo.gl.ShaderUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Demo 5：多模型对比渲染器 —— 面数递增 + 概念可视化
 *
 * 两组模型，点击「下一个模型」切换：
 *
 * ── 第一组：面数递增（点线面 → 3D 模型）──
 *   ① 四面体    — 最少能围出 3D 空间的面数
 *   ② 金字塔    — 加底面，变成熟悉形状
 *   ③ 八面体    — 更多面，更对称
 *   ④ 低模球    — 棱角分明的球
 *   ⑤ 高模球    — 面够多就光滑了
 *   ⑥ 环形体    — 复杂曲面也是三角形拼的
 *
 * ── 第二组：概念可视化（文档核心原理）──
 *   ⑦ 坐标轴    — XYZ 三轴，展示坐标空间 & 坐标变换
 *   ⑧ 方向箭头  — 表示光线/法线/相机朝向等向量
 *   ⑨ 视锥体    — 相机能看到的锥形区域，透视投影的几何意义
 *   ⑩ 楼梯      — 不同朝向的面在同一光源下亮度不同 = 法线决定光照
 *   ⑪ 波浪面    — 每个点法线都不同，完美演示 Blinn-Phong 明暗变化
 */
class ModelRenderer(private val context: Context) : DemoRenderer {

    @Volatile override var rotationX = -20f
    @Volatile override var rotationY = 30f
    @Volatile override var cameraDistance = 2.8f

    /** 当前模型的描述信息（供 Compose UI 读取显示） */
    @Volatile var modelInfo = ""

    private var program = 0
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

    /** 单个预加载模型的 GPU 缓冲数据 */
    private class LoadedModel(
        val label: String,
        val vertexBuf: FloatBuffer,
        val indexBuf: ShortBuffer,
        val indexCount: Int,
    )

    private val models = mutableListOf<LoadedModel>()
    private var activeIndex = 0

    /** 切换到下一个模型（从 Compose UI 调用） */
    fun nextModel() {
        if (models.isNotEmpty()) {
            activeIndex = (activeIndex + 1) % models.size
            updateInfo()
        }
    }

    private fun updateInfo() {
        if (models.isNotEmpty()) {
            modelInfo = models[activeIndex].label
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.06f, 0.06f, 0.10f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

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

        // ─── 预加载所有模型 ───
        // 第一组：面数递增，展示「面数越多越精细」
        // 第二组：概念可视化，展示坐标系、相机、光照等原理
        val meshes = listOf(
            "① 四面体" to loadObjSafe("models/tetrahedron.obj"),
            "② 金字塔" to loadObjSafe("models/pyramid.obj"),
            "③ 八面体" to loadObjSafe("models/sample.obj"),
            "④ 低模球" to ObjLoader.generateSphere(0.8f, 3, 6),
            "⑤ 高模球" to ObjLoader.generateSphere(0.8f, 16, 24),
            "⑥ 环形体" to ObjLoader.generateTorus(),
            "⑦ 坐标轴" to ObjLoader.generateAxes(),
            "⑧ 方向箭头" to loadObjSafe("models/arrow.obj"),
            "⑨ 视锥体" to loadObjSafe("models/frustum.obj"),
            "⑩ 楼梯" to loadObjSafe("models/stairs.obj"),
            "⑪ 波浪面" to ObjLoader.generateWavySurface(),
        )

        for ((name, mesh) in meshes) {
            val label = "$name — ${mesh.vertexCount} 个顶点 · ${mesh.triangleCount} 个三角面"
            models.add(LoadedModel(label, buildVertexBuf(mesh), buildIndexBuf(mesh), mesh.indices.size))
        }

        updateInfo()
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        Matrix.perspectiveM(projMatrix, 0, 45f, w.toFloat() / h, 1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        if (models.isEmpty()) return
        val model = models[activeIndex]

        rotationY += 0.25f

        // ─── MVP 矩阵 ───
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.rotateM(modelMatrix, 0, rotationX, 1f, 0f, 0f)
        Matrix.rotateM(modelMatrix, 0, rotationY, 0f, 1f, 0f)
        Matrix.multiplyMM(temp, 0, viewMatrix, 0, modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, temp, 0)

        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(hMVP, 1, false, mvpMatrix, 0)
        GLES30.glUniformMatrix4fv(hModel, 1, false, modelMatrix, 0)

        GLES30.glUniform3f(hLightDir, 0.48f, 0.72f, 0.50f)
        GLES30.glUniform3f(hLightColor, 1.0f, 0.95f, 0.88f)
        GLES30.glUniform3f(hAmbient, 0.10f, 0.10f, 0.15f)
        GLES30.glUniform3f(hObjColor, 0.50f, 0.72f, 0.85f)
        GLES30.glUniform3f(hCamPos, 0f, 0f, cameraDistance)
        GLES30.glUniform1f(hShininess, 48f)

        val stride = 6 * 4
        model.vertexBuf.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, model.vertexBuf)
        GLES30.glEnableVertexAttribArray(0)

        model.vertexBuf.position(3)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, model.vertexBuf)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawElements(
            GLES30.GL_TRIANGLES, model.indexCount, GLES30.GL_UNSIGNED_SHORT, model.indexBuf
        )

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }

    // ─── 工具方法 ───

    private fun loadObjSafe(path: String): MeshData = try {
        ObjLoader.loadObj(context, path)
    } catch (_: Exception) {
        ObjLoader.generateTorus()
    }

    private fun buildVertexBuf(mesh: MeshData): FloatBuffer {
        val data = FloatArray(mesh.vertexCount * 6)
        for (i in 0 until mesh.vertexCount) {
            data[i * 6 + 0] = mesh.positions[i * 3 + 0]
            data[i * 6 + 1] = mesh.positions[i * 3 + 1]
            data[i * 6 + 2] = mesh.positions[i * 3 + 2]
            data[i * 6 + 3] = mesh.normals[i * 3 + 0]
            data[i * 6 + 4] = mesh.normals[i * 3 + 1]
            data[i * 6 + 5] = mesh.normals[i * 3 + 2]
        }
        return ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(data).apply { position(0) }
    }

    private fun buildIndexBuf(mesh: MeshData): ShortBuffer {
        return ByteBuffer.allocateDirect(mesh.indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(mesh.indices).apply { position(0) }
    }
}
