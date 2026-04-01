package com.demo.a3ddemo.gl

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log

/**
 * 演示渲染器的公共接口
 *
 * 所有 Demo 渲染器都实现此接口，统一管理触控交互状态：
 * - rotationX / rotationY：单指拖动的旋转角度
 * - cameraDistance：双指缩放控制的摄像机距离
 */
interface DemoRenderer : GLSurfaceView.Renderer {
    /** 绕 X 轴旋转角度（上下拖动） */
    var rotationX: Float
    /** 绕 Y 轴旋转角度（左右拖动） */
    var rotationY: Float
    /** 摄像机到物体的距离（双指缩放） */
    var cameraDistance: Float
}

/**
 * Shader 编译与程序链接工具
 *
 * 对应文档：「Shader 是一小段运行在 GPU 上的程序」
 *
 * 工作流程：
 *   1. 从 assets/shaders/ 加载 GLSL 源码文本
 *   2. 分别编译顶点着色器(VS)和片元着色器(FS)
 *   3. 链接成一个可用的 GPU 程序(Program)
 *   4. 返回 program ID，后续通过 glUseProgram 激活
 */
object ShaderUtils {
    private const val TAG = "ShaderUtils"

    /**
     * 从 assets 目录加载 GLSL 文件并编译链接
     *
     * @param context      Android 上下文（用于读取 assets）
     * @param vertexPath   顶点着色器文件路径，如 "shaders/basic_cube.vert"
     * @param fragmentPath 片元着色器文件路径，如 "shaders/basic_cube.frag"
     * @return GPU 程序 ID，失败返回 0
     */
    fun createProgram(context: Context, vertexPath: String, fragmentPath: String): Int {
        val vsSrc = context.assets.open(vertexPath).bufferedReader().use { it.readText() }
        val fsSrc = context.assets.open(fragmentPath).bufferedReader().use { it.readText() }
        return createProgram(vsSrc, fsSrc)
    }

    /**
     * 从源码字符串编译并链接 Shader 程序
     */
    fun createProgram(vertexSource: String, fragmentSource: String): Int {
        // 分别编译两个着色器
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        if (vs == 0 || fs == 0) return 0

        // 创建程序并链接
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vs)
        GLES30.glAttachShader(program, fs)
        GLES30.glLinkProgram(program)

        // 检查链接结果
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }

        // 链接完成后可以释放 shader 对象
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return program
    }

    /**
     * 编译单个着色器
     */
    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)

        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val label = if (type == GLES30.GL_VERTEX_SHADER) "VS" else "FS"
            Log.e(TAG, "Shader compile error ($label): ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }
}
