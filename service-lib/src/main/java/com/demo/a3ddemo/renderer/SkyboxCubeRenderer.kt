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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Demo 4：天空盒 + 环境反射正方体
 *
 * 对应文档章节：
 *   「三、天空盒(Skybox) — 展厅四周的背景」
 *
 * 展示的核心概念：
 *   - 天空盒(Skybox)：6 张图拼成一个包围场景的大立方体，营造完整环境
 *   - 立方体贴图(CubeMap)：用方向向量采样，GPU 自动选择对应面
 *   - 环境反射：正方体表面反射天空盒图像
 *     文档：「车漆表面反射出的周围环境，其实就是天空盒的图像」
 *
 * 渲染流程：
 *   ① 先画天空盒（关闭深度测试，填满整个屏幕背景）
 *   ② 再画反射正方体（开启深度测试，叠加在天空盒上方）
 *
 * 与前三个 Demo 不同，这里用摄像机环绕(orbit)代替模型旋转，
 * 这样天空盒能随视角变化，环境反射效果更自然。
 */
class SkyboxCubeRenderer(private val context: Context) : DemoRenderer {

    @Volatile override var rotationX = -15f
    @Volatile override var rotationY = 45f
    @Volatile override var cameraDistance = 3.5f

    // 两个独立的 GPU 程序：天空盒 + 反射正方体
    private var skyProgram = 0
    private var cubeProgram = 0
    private var cubemapTex = 0   // CubeMap 纹理 ID（天空盒和反射共用）

    // 天空盒 Shader 的 uniform 句柄
    private var hSkyVP = 0
    private var hSkySampler = 0

    // 反射正方体 Shader 的 uniform 句柄
    private var hCubeMVP = 0
    private var hCubeSampler = 0
    private var hCubeCamPos = 0

    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val mvpMatrix  = FloatArray(16)
    private val skyboxVP   = FloatArray(16)
    private val temp       = FloatArray(16)

    private lateinit var skyVtxBuf: FloatBuffer
    private lateinit var cubeVtxBuf: FloatBuffer
    private lateinit var cubeIdxBuf: ShortBuffer

    // 天空盒顶点：36 个(6 面 × 2 三角形 × 3 顶点)，仅位置
    // 从内部观看，所以后续渲染时关闭面剔除
    private val skyboxVerts = floatArrayOf(
        // +X 面
         1f,-1f,-1f,   1f,-1f, 1f,   1f, 1f, 1f,   1f, 1f, 1f,   1f, 1f,-1f,   1f,-1f,-1f,
        // -X 面
        -1f,-1f, 1f,  -1f,-1f,-1f,  -1f, 1f,-1f,  -1f, 1f,-1f,  -1f, 1f, 1f,  -1f,-1f, 1f,
        // +Y 面
        -1f, 1f, 1f,  -1f, 1f,-1f,   1f, 1f,-1f,   1f, 1f,-1f,   1f, 1f, 1f,  -1f, 1f, 1f,
        // -Y 面
        -1f,-1f,-1f,  -1f,-1f, 1f,   1f,-1f, 1f,   1f,-1f, 1f,   1f,-1f,-1f,  -1f,-1f,-1f,
        // +Z 面
        -1f,-1f, 1f,  -1f, 1f, 1f,   1f, 1f, 1f,   1f, 1f, 1f,   1f,-1f, 1f,  -1f,-1f, 1f,
        // -Z 面
         1f,-1f,-1f,   1f, 1f,-1f,  -1f, 1f,-1f,  -1f, 1f,-1f,  -1f,-1f,-1f,   1f,-1f,-1f,
    )

    // 反射正方体顶点：位置(3) + 法线(3)
    // 法线用于计算反射方向
    private val cubeVerts = floatArrayOf(
        // 前面 — 法线 (0,0,1)
        -0.5f,-0.5f, 0.5f,  0f, 0f, 1f,   0.5f,-0.5f, 0.5f,  0f, 0f, 1f,
         0.5f, 0.5f, 0.5f,  0f, 0f, 1f,  -0.5f, 0.5f, 0.5f,  0f, 0f, 1f,
        // 后面 — 法线 (0,0,-1)
         0.5f,-0.5f,-0.5f,  0f, 0f,-1f,  -0.5f,-0.5f,-0.5f,  0f, 0f,-1f,
        -0.5f, 0.5f,-0.5f,  0f, 0f,-1f,   0.5f, 0.5f,-0.5f,  0f, 0f,-1f,
        // 左面 — 法线 (-1,0,0)
        -0.5f,-0.5f,-0.5f, -1f, 0f, 0f,  -0.5f,-0.5f, 0.5f, -1f, 0f, 0f,
        -0.5f, 0.5f, 0.5f, -1f, 0f, 0f,  -0.5f, 0.5f,-0.5f, -1f, 0f, 0f,
        // 右面 — 法线 (1,0,0)
         0.5f,-0.5f, 0.5f,  1f, 0f, 0f,   0.5f,-0.5f,-0.5f,  1f, 0f, 0f,
         0.5f, 0.5f,-0.5f,  1f, 0f, 0f,   0.5f, 0.5f, 0.5f,  1f, 0f, 0f,
        // 顶面 — 法线 (0,1,0)
        -0.5f, 0.5f, 0.5f,  0f, 1f, 0f,   0.5f, 0.5f, 0.5f,  0f, 1f, 0f,
         0.5f, 0.5f,-0.5f,  0f, 1f, 0f,  -0.5f, 0.5f,-0.5f,  0f, 1f, 0f,
        // 底面 — 法线 (0,-1,0)
        -0.5f,-0.5f,-0.5f,  0f,-1f, 0f,   0.5f,-0.5f,-0.5f,  0f,-1f, 0f,
         0.5f,-0.5f, 0.5f,  0f,-1f, 0f,  -0.5f,-0.5f, 0.5f,  0f,-1f, 0f,
    )

    private val cubeIndices = shortArrayOf(
         0,  1,  2,   0,  2,  3,
         4,  5,  6,   4,  6,  7,
         8,  9, 10,   8, 10, 11,
        12, 13, 14,  12, 14, 15,
        16, 17, 18,  16, 18, 19,
        20, 21, 22,  20, 22, 23,
    )

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // 天空盒 Shader
        skyProgram = ShaderUtils.createProgram(
            context, "shaders/skybox.vert", "shaders/skybox.frag"
        )
        hSkyVP      = GLES30.glGetUniformLocation(skyProgram, "uVP")
        hSkySampler = GLES30.glGetUniformLocation(skyProgram, "uSkybox")

        // 反射正方体 Shader
        cubeProgram = ShaderUtils.createProgram(
            context, "shaders/reflect_cube.vert", "shaders/reflect_cube.frag"
        )
        hCubeMVP     = GLES30.glGetUniformLocation(cubeProgram, "uMVP")
        hCubeSampler = GLES30.glGetUniformLocation(cubeProgram, "uSkybox")
        hCubeCamPos  = GLES30.glGetUniformLocation(cubeProgram, "uCamPos")

        // 生成天空盒 CubeMap（天空盒和反射共用同一张 CubeMap）
        cubemapTex = TextureHelper.createSkyboxCubemap()

        // 准备顶点缓冲
        skyVtxBuf = ByteBuffer.allocateDirect(skyboxVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(skyboxVerts).apply { position(0) }

        cubeVtxBuf = ByteBuffer.allocateDirect(cubeVerts.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(cubeVerts).apply { position(0) }

        cubeIdxBuf = ByteBuffer.allocateDirect(cubeIndices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer()
            .put(cubeIndices).apply { position(0) }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES30.glViewport(0, 0, w, h)
        Matrix.perspectiveM(projMatrix, 0, 45f, w.toFloat() / h, 1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        rotationY += 0.12f

        // ─── 计算摄像机环绕位置 ───
        // 将触控角度转为球面坐标，摄像机绕原点做轨道运动
        val radX = Math.toRadians(rotationX.toDouble())
        val radY = Math.toRadians(rotationY.toDouble())
        val dist = cameraDistance
        val eyeX = (dist * sin(radY) * cos(radX)).toFloat()
        val eyeY = (dist * sin(radX)).toFloat()
        val eyeZ = (dist * cos(radY) * cos(radX)).toFloat()

        // 视图矩阵：摄像机在轨道位置，始终看向原点
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)

        // ═══ 第一遍：渲染天空盒（背景）═══
        // 文档：「天空盒就是展厅四周墙上的巨幅背景画」

        // 构建天空盒的 VP 矩阵：去掉视图矩阵的平移分量，只保留旋转
        // 这样天空盒就像「无限远处」的背景，不随摄像机位移
        val skyView = FloatArray(16)
        System.arraycopy(viewMatrix, 0, skyView, 0, 16)
        skyView[12] = 0f; skyView[13] = 0f; skyView[14] = 0f  // 清除平移
        Matrix.multiplyMM(skyboxVP, 0, projMatrix, 0, skyView, 0)

        // 关闭深度测试 — 天空盒始终在最远处
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)

        GLES30.glUseProgram(skyProgram)
        GLES30.glUniformMatrix4fv(hSkyVP, 1, false, skyboxVP, 0)

        // 绑定 CubeMap 纹理
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, cubemapTex)
        GLES30.glUniform1i(hSkySampler, 0)

        // 画天空盒（36 个顶点，无索引）
        skyVtxBuf.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 0, skyVtxBuf)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 36)
        GLES30.glDisableVertexAttribArray(0)

        // ═══ 第二遍：渲染反射正方体 ═══
        // 文档：「车漆表面反射出的周围环境，其实就是天空盒的图像」

        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // MVP = P × V（模型矩阵为单位矩阵，正方体固定在原点）
        Matrix.multiplyMM(mvpMatrix, 0, projMatrix, 0, viewMatrix, 0)

        GLES30.glUseProgram(cubeProgram)
        GLES30.glUniformMatrix4fv(hCubeMVP, 1, false, mvpMatrix, 0)
        GLES30.glUniform3f(hCubeCamPos, eyeX, eyeY, eyeZ)  // 摄像机位置（算反射方向用）

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, cubemapTex)
        GLES30.glUniform1i(hCubeSampler, 0)

        // 绑定顶点属性：位置 + 法线
        val stride = 6 * 4
        cubeVtxBuf.position(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, cubeVtxBuf)
        GLES30.glEnableVertexAttribArray(0)
        cubeVtxBuf.position(3)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, cubeVtxBuf)
        GLES30.glEnableVertexAttribArray(1)

        GLES30.glDrawElements(GLES30.GL_TRIANGLES, 36, GLES30.GL_UNSIGNED_SHORT, cubeIdxBuf)

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
    }
}
