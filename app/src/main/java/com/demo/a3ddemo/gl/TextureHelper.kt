package com.demo.a3ddemo.gl

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.opengl.GLES30
import android.opengl.GLUtils

/**
 * 纹理生成工具
 *
 * 对应文档：「纹理(Texture) — 各种表面素材图」
 *
 * 纹理不只是颜色图，它有很多种（颜色贴图、法线贴图、粗糙度贴图……）。
 * 这里为演示用途，通过代码动态生成纹理图像：
 *   - 棋盘格纹理（Demo 2）：展示 UV 映射如何把 2D 图贴到 3D 面
 *   - 天空盒立方体贴图（Demo 4）：6 张渐变图拼成环境背景
 */
object TextureHelper {

    /**
     * 创建棋盘格纹理
     *
     * 对应文档：「颜色贴图(Albedo) — 物体的底色」
     *
     * 特征设计：
     *   - 黑白棋盘格：清晰展示 UV 映射的对应关系
     *   - 蓝色边框：标记纹理的边缘（能看出每个面使用完整的 0~1 UV 范围）
     *   - 红色十字线：标记纹理中心（方便观察 UV 坐标原点）
     *
     * @return OpenGL 纹理 ID
     */
    fun createCheckerboardTexture(): Int {
        val size = 512
        val grid = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint().apply { isAntiAlias = false }

        // 绘制棋盘格
        for (row in 0 until size / grid) {
            for (col in 0 until size / grid) {
                paint.color = if ((row + col) % 2 == 0)
                    Color.rgb(235, 235, 235) else Color.rgb(75, 75, 75)
                paint.shader = null
                paint.style = Paint.Style.FILL
                canvas.drawRect(
                    col * grid.toFloat(), row * grid.toFloat(),
                    (col + 1) * grid.toFloat(), (row + 1) * grid.toFloat(), paint
                )
            }
        }

        // 蓝色边框 — 标记纹理边缘
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.color = Color.rgb(50, 130, 220)
        canvas.drawRect(3f, 3f, size - 3f, size - 3f, paint)

        // 红色十字线 — 标记纹理中心
        paint.strokeWidth = 2f
        paint.color = Color.rgb(220, 60, 60)
        canvas.drawLine(size / 2f, 0f, size / 2f, size.toFloat(), paint)
        canvas.drawLine(0f, size / 2f, size.toFloat(), size / 2f, paint)

        return uploadTexture2D(bmp)
    }

    /**
     * 创建天空盒立方体贴图 (CubeMap)
     *
     * 对应文档：「天空盒 — 展厅四周墙上的巨幅背景画」
     *
     * 立方体贴图由 6 张图组成，分别对应正方体的 6 个面：
     *   +X(右), -X(左), +Y(顶), -Y(底), +Z(后), -Z(前)
     *
     * 这里生成日落风格的渐变天空：
     *   - 顶面：深蓝色夜空
     *   - 底面：暗色地面
     *   - 四个侧面：从深蓝到暖橙的日落渐变
     *
     * 天空盒还有一个隐藏作用：环境反射。
     * 车漆表面反射出的「周围环境」，其实就是天空盒的图像。
     *
     * @return OpenGL CubeMap 纹理 ID
     */
    fun createSkyboxCubemap(): Int {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_CUBE_MAP, texIds[0])

        // CubeMap 的 6 个面，顺序固定
        val faces = intArrayOf(
            GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_X,  // 右
            GLES30.GL_TEXTURE_CUBE_MAP_NEGATIVE_X,  // 左
            GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_Y,  // 上
            GLES30.GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,  // 下
            GLES30.GL_TEXTURE_CUBE_MAP_POSITIVE_Z,  // 后
            GLES30.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z   // 前
        )

        // 为每个面生成渐变纹理并上传到 GPU
        for ((i, face) in faces.withIndex()) {
            val bmp = createSkyFace(i)
            GLUtils.texImage2D(face, 0, bmp, 0)
            bmp.recycle()
        }

        // 设置采样参数：线性过滤 + 边缘钳制
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_CUBE_MAP, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        return texIds[0]
    }

    /**
     * 生成天空盒单个面的渐变位图
     *
     * @param faceIndex 面索引：0=+X, 1=-X, 2=+Y(顶), 3=-Y(底), 4=+Z, 5=-Z
     */
    private fun createSkyFace(faceIndex: Int): Bitmap {
        val s = 256
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()

        when (faceIndex) {
            2 -> { // +Y 顶面 — 深蓝夜空
                paint.shader = LinearGradient(
                    0f, 0f, 0f, s.toFloat(),
                    Color.rgb(8, 8, 35), Color.rgb(25, 25, 70),
                    Shader.TileMode.CLAMP
                )
            }
            3 -> { // -Y 底面 — 暗色地面
                paint.shader = LinearGradient(
                    0f, 0f, 0f, s.toFloat(),
                    Color.rgb(45, 38, 30), Color.rgb(22, 18, 14),
                    Shader.TileMode.CLAMP
                )
            }
            else -> { // 四个侧面 — 日落渐变（深蓝→暖橙）
                paint.shader = LinearGradient(
                    0f, 0f, 0f, s.toFloat(),
                    intArrayOf(
                        Color.rgb(12, 12, 55),    // 天顶：深蓝
                        Color.rgb(35, 45, 95),    // 高空：蓝紫
                        Color.rgb(170, 110, 55),  // 地平线：暖橙
                        Color.rgb(210, 130, 45)   // 地面附近：金橙
                    ),
                    floatArrayOf(0f, 0.4f, 0.78f, 1f),
                    Shader.TileMode.CLAMP
                )
            }
        }
        canvas.drawRect(0f, 0f, s.toFloat(), s.toFloat(), paint)
        return bmp
    }

    /**
     * 上传 Bitmap 为 OpenGL 2D 纹理
     *
     * 设置线性过滤(缩小时平滑) + Mipmap(多级渐远纹理提高远处显示质量)
     */
    private fun uploadTexture2D(bitmap: Bitmap): Int {
        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texIds[0])

        // 缩小时使用 Mipmap + 线性插值，放大时线性插值
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)

        // 上传位图数据到 GPU 显存
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        // 自动生成 Mipmap 金字塔
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        bitmap.recycle()
        return texIds[0]
    }
}
