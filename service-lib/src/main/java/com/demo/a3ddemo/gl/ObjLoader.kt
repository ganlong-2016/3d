package com.demo.a3ddemo.gl

import android.content.Context
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 3D 模型数据容器
 *
 * 无论从 OBJ 文件解析还是程序化生成，最终都归结为这三组数据。
 * 这就是文档所说的「Mesh（网格）= 顶点 + 三角形」。
 *
 * @property positions 顶点位置数组 [x,y,z, x,y,z, ...]
 * @property normals   顶点法线数组 [nx,ny,nz, ...]（与 positions 一一对应）
 * @property indices   三角形索引数组，每 3 个构成一个三角形
 */
data class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: ShortArray,
) {
    /** 顶点总数 */
    val vertexCount get() = positions.size / 3
    /** 三角形总数（= 文档中的「面数 Poly Count」）*/
    val triangleCount get() = indices.size / 3
}

/**
 * 3D 模型加载与生成工具
 *
 * 对应文档：
 *   「Mesh — 白车身，用三角形拼出物体的形状」
 *   「面数越多越精细，但 GPU 计算量越大」
 *
 * 3D 模型文件（.obj / .glTF / .fbx）本质上就是顶点数据的集合。
 * 加载模型 = 解析文件 → 提取顶点/法线/索引 → 送入 GPU 渲染管线。
 * 渲染步骤与之前的正方体完全一致，只是顶点数据量更大。
 *
 * ─── 常见 3D 模型格式 ───
 *
 *  格式         特点                              解析难度
 *  .obj         纯文本，最简单，无动画              ★☆☆  ← 本工具直接支持
 *  .glTF/.glb   现代标准「3D 界的 JPEG」，支持 PBR  ★★☆  需要 jglTF 等库
 *  .fbx         Autodesk 工业标准，功能全           ★★★  需要 Assimp / FBX SDK
 *  .stl         3D 打印格式，仅三角面              ★☆☆  简单但无法线/UV
 *  .dae         XML 格式，冗长                     ★★☆
 */
object ObjLoader {

    /**
     * 从 assets 加载 Wavefront OBJ 文件
     *
     * OBJ 是最简单的 3D 模型格式，纯文本，人类可读。
     * 核心关键字只有 4 个：
     *
     *   v  x y z       — 顶点位置 (Vertex Position)
     *   vn nx ny nz    — 顶点法线 (Vertex Normal)
     *   vt u v         — 纹理坐标 (Texture Coordinate)
     *   f  v//vn ...   — 面定义   (Face，三角形或多边形)
     *
     * 解析流程：
     *   ① 逐行读取，收集所有 v 和 vn 数据到临时列表
     *   ② 解析 f 行，将 OBJ 的多重索引 (pos/uv/norm) 合并为单一索引
     *      （因为 OBJ 对 position、normal、uv 使用独立索引，
     *       而 OpenGL 要求统一的顶点索引）
     *   ③ 输出 OpenGL 可直接使用的顶点数组和索引数组
     *
     * @param context   Android 上下文
     * @param assetPath assets 中的文件路径，如 "models/sample.obj"
     */
    fun loadObj(context: Context, assetPath: String): MeshData {
        // ─── 第一阶段：收集原始数据 ───
        val rawPos = mutableListOf<FloatArray>()    // 所有 v 行
        val rawNorm = mutableListOf<FloatArray>()   // 所有 vn 行

        // OBJ 的 pos 和 normal 使用独立索引，需要建立映射表
        // 将 (posIndex, normIndex) 组合映射为唯一的 OpenGL 顶点索引
        data class VertexKey(val pi: Int, val ni: Int)
        val vertexMap = LinkedHashMap<VertexKey, Int>()

        val outPos = mutableListOf<Float>()
        val outNorm = mutableListOf<Float>()
        val outIdx = mutableListOf<Short>()

        // ─── 第二阶段：逐行解析 ───
        context.assets.open(assetPath).bufferedReader().forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split("\\s+".toRegex())

            when {
                // 顶点位置：v x y z
                line.startsWith("v ") && parts.size >= 4 -> {
                    rawPos.add(floatArrayOf(
                        parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()
                    ))
                }

                // 顶点法线：vn nx ny nz
                line.startsWith("vn ") && parts.size >= 4 -> {
                    rawNorm.add(floatArrayOf(
                        parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()
                    ))
                }

                // 面定义：f v1//vn1 v2//vn2 v3//vn3
                // 也支持 f v1/vt1/vn1 ... 和 f v1 v2 v3 格式
                line.startsWith("f ") && parts.size >= 4 -> {
                    val faceVerts = parts.drop(1).map { token ->
                        val comps = token.split("/")
                        val pi = comps[0].toInt() - 1  // OBJ 索引从 1 开始，转为 0-based
                        val ni = if (comps.size >= 3 && comps[2].isNotEmpty())
                            comps[2].toInt() - 1 else 0

                        // 查找或创建统一索引
                        vertexMap.getOrPut(VertexKey(pi, ni)) {
                            val idx = outPos.size / 3
                            outPos.addAll(rawPos[pi].toList())
                            if (rawNorm.isNotEmpty() && ni < rawNorm.size) {
                                outNorm.addAll(rawNorm[ni].toList())
                            } else {
                                outNorm.addAll(listOf(0f, 1f, 0f))
                            }
                            idx
                        }
                    }

                    // 扇形三角化（fan triangulation）
                    // 支持三角形(3 顶点)和多边形(4+ 顶点)的面
                    for (i in 1 until faceVerts.size - 1) {
                        outIdx.add(faceVerts[0].toShort())
                        outIdx.add(faceVerts[i].toShort())
                        outIdx.add(faceVerts[i + 1].toShort())
                    }
                }
            }
        }

        return MeshData(
            outPos.toFloatArray(), outNorm.toFloatArray(), outIdx.toShortArray()
        )
    }

    /**
     * 程序化生成环形体 (Torus)
     *
     * 与从文件加载的流程完全等价——最终都是生成顶点位置、法线和三角形索引。
     * 这证明了一个核心原理：
     *   无论数据来自文件还是算法，渲染管线都是同一套。
     *
     * 环形体结构：一个小圆（管道截面）沿大圆（环中心线）扫掠而成。
     *
     * @param majorR   大圆半径（环的中心线到原点的距离）
     * @param minorR   小圆半径（管道的粗细）
     * @param ringSegs 沿环方向的分段数（越多越圆滑）
     * @param tubeSegs 沿管截面方向的分段数
     */
    fun generateTorus(
        majorR: Float = 0.6f,
        minorR: Float = 0.25f,
        ringSegs: Int = 48,
        tubeSegs: Int = 24,
    ): MeshData {
        val pi2 = (Math.PI * 2).toFloat()
        val positions = mutableListOf<Float>()
        val normals = mutableListOf<Float>()

        // ─── 生成顶点网格 ───
        // 两层循环：i 绕环方向(大圆)，j 绕管截面(小圆)
        for (i in 0..ringSegs) {
            val u = pi2 * i / ringSegs
            val cu = cos(u); val su = sin(u)

            for (j in 0..tubeSegs) {
                val v = pi2 * j / tubeSegs
                val cv = cos(v); val sv = sin(v)

                // 顶点位置：大圆半径 + 小圆偏移
                val x = (majorR + minorR * cv) * cu
                val y = minorR * sv
                val z = (majorR + minorR * cv) * su
                positions.addAll(listOf(x, y, z))

                // 法线方向 = 从管圆中心指向顶点（标准化）
                val nx = cv * cu
                val ny = sv
                val nz = cv * su
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                normals.addAll(listOf(nx / len, ny / len, nz / len))
            }
        }

        // ─── 生成三角形索引 ───
        // 每个格子(quad)拆成 2 个三角形
        val indices = mutableListOf<Short>()
        val cols = tubeSegs + 1
        for (i in 0 until ringSegs) {
            for (j in 0 until tubeSegs) {
                val a = (i * cols + j).toShort()
                val b = ((i + 1) * cols + j).toShort()
                val c = ((i + 1) * cols + j + 1).toShort()
                val d = (i * cols + j + 1).toShort()
                indices.addAll(listOf(a, b, c, a, c, d))
            }
        }

        return MeshData(
            positions.toFloatArray(), normals.toFloatArray(), indices.toShortArray()
        )
    }

    /**
     * 程序化生成 UV 球体
     *
     * 用经纬线网格生成球面，面数越多越光滑。
     * 对比不同参数可以直观看到「面数越多越精细」：
     *   stacks=3, slices=6   → 36 面（棱角分明）
     *   stacks=16, slices=24 → 768 面（接近光滑）
     *
     * @param radius 球体半径
     * @param stacks 纬度分段数（从南极到北极）
     * @param slices 经度分段数（绕一圈）
     */
    fun generateSphere(
        radius: Float = 0.8f,
        stacks: Int = 16,
        slices: Int = 24,
    ): MeshData {
        val positions = mutableListOf<Float>()
        val normals = mutableListOf<Float>()

        for (i in 0..stacks) {
            val phi = Math.PI * i / stacks          // 0(北极) → π(南极)
            val y = cos(phi).toFloat()
            val r = sin(phi).toFloat()

            for (j in 0..slices) {
                val theta = 2.0 * Math.PI * j / slices  // 0 → 2π 绕一圈
                val x = (r * cos(theta)).toFloat()
                val z = (r * sin(theta)).toFloat()

                // 单位球上，顶点位置就是法线方向
                normals.addAll(listOf(x, y, z))
                positions.addAll(listOf(x * radius, y * radius, z * radius))
            }
        }

        val indices = mutableListOf<Short>()
        val cols = slices + 1
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = (i * cols + j).toShort()
                val b = ((i + 1) * cols + j).toShort()
                val c = ((i + 1) * cols + j + 1).toShort()
                val d = (i * cols + j + 1).toShort()
                indices.addAll(listOf(a, b, c, a, c, d))
            }
        }

        return MeshData(
            positions.toFloatArray(), normals.toFloatArray(), indices.toShortArray()
        )
    }

    // ═══════════════════════════════════════════════════════════════
    //  下面的生成器用于「概念可视化」模型 —— 展示坐标系、光照等原理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 程序化生成 XYZ 坐标轴
     *
     * 三个箭头分别指向 X、Y、Z 正方向，中心有一个小方块。
     * 每个箭头 = 矩形杆身 + 四棱锥箭尖。
     *
     * 对应文档：「坐标变换 — 模型空间 → 世界空间 → 观察空间」
     * 旋转这个模型，就能看到坐标轴随之旋转——这就是坐标变换的本质。
     */
    fun generateAxes(): MeshData {
        val pos = mutableListOf<Float>()
        val nrm = mutableListOf<Float>()
        val idx = mutableListOf<Short>()

        // 沿 dir 方向生成一个箭头，u/v 是垂直于 dir 的两个方向
        fun addArrow(
            dx: Float, dy: Float, dz: Float,
            ux: Float, uy: Float, uz: Float,
            vx: Float, vy: Float, vz: Float,
        ) {
            val sLen = 0.55f; val tLen = 0.25f
            val sr = 0.018f; val tr = 0.05f
            val base = pos.size / 3
            val corners = arrayOf(
                floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f),
                floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f),
            )

            // 杆身 8 个顶点（近端 4 + 远端 4）
            for (t in floatArrayOf(0f, sLen)) {
                for (c in corners) {
                    val su = c[0]; val sv = c[1]
                    pos.add(dx * t + ux * su * sr + vx * sv * sr)
                    pos.add(dy * t + uy * su * sr + vy * sv * sr)
                    pos.add(dz * t + uz * su * sr + vz * sv * sr)
                    val nx = ux * su + vx * sv
                    val ny = uy * su + vy * sv
                    val nz = uz * su + vz * sv
                    val l = sqrt(nx * nx + ny * ny + nz * nz)
                    nrm.addAll(listOf(nx / l, ny / l, nz / l))
                }
            }

            fun quad(a: Int, b: Int, c: Int, d: Int) {
                idx.add((base + a).toShort()); idx.add((base + b).toShort()); idx.add((base + c).toShort())
                idx.add((base + a).toShort()); idx.add((base + c).toShort()); idx.add((base + d).toShort())
            }
            quad(0, 1, 5, 4); quad(1, 2, 6, 5); quad(2, 3, 7, 6); quad(3, 0, 4, 7)
            idx.addAll(listOf((base + 3).toShort(), (base + 2).toShort(), (base + 1).toShort()))
            idx.addAll(listOf((base + 3).toShort(), (base + 1).toShort(), (base + 0).toShort()))

            // 箭尖 5 个顶点（底面 4 + 尖端 1）
            val tipBase = pos.size / 3
            for (c in corners) {
                val su = c[0]; val sv = c[1]
                pos.add(dx * sLen + ux * su * tr + vx * sv * tr)
                pos.add(dy * sLen + uy * su * tr + vy * sv * tr)
                pos.add(dz * sLen + uz * su * tr + vz * sv * tr)
                val nx = ux * su * 0.3f + vx * sv * 0.3f + dx
                val ny = uy * su * 0.3f + vy * sv * 0.3f + dy
                val nz = uz * su * 0.3f + vz * sv * 0.3f + dz
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                nrm.addAll(listOf(nx / l, ny / l, nz / l))
            }
            pos.addAll(listOf(dx * (sLen + tLen), dy * (sLen + tLen), dz * (sLen + tLen)))
            nrm.addAll(listOf(dx, dy, dz))
            val apex = tipBase + 4
            for (i in 0..3) {
                idx.add((tipBase + i).toShort())
                idx.add((tipBase + (i + 1) % 4).toShort())
                idx.add(apex.toShort())
            }
        }

        addArrow(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f) // X 轴
        addArrow(0f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 0f) // Y 轴
        addArrow(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f) // Z 轴

        // 原点小方块
        val cb = pos.size / 3
        val s = 0.035f
        for (sx in listOf(-1f, 1f)) {
            for (sy in listOf(-1f, 1f)) {
                for (sz in listOf(-1f, 1f)) {
                    pos.addAll(listOf(sx * s, sy * s, sz * s))
                    val l = sqrt(3f)
                    nrm.addAll(listOf(sx / l, sy / l, sz / l))
                }
            }
        }
        val cq = arrayOf(
            intArrayOf(4, 6, 7, 5), intArrayOf(0, 1, 3, 2),
            intArrayOf(2, 3, 7, 6), intArrayOf(0, 4, 5, 1),
            intArrayOf(1, 5, 7, 3), intArrayOf(0, 2, 6, 4),
        )
        for (q in cq) {
            idx.add((cb + q[0]).toShort()); idx.add((cb + q[1]).toShort()); idx.add((cb + q[2]).toShort())
            idx.add((cb + q[0]).toShort()); idx.add((cb + q[2]).toShort()); idx.add((cb + q[3]).toShort())
        }

        return MeshData(pos.toFloatArray(), nrm.toFloatArray(), idx.toShortArray())
    }

    /**
     * 程序化生成波浪面 — 法线与光照的终极演示
     *
     * 一个平面加上正弦波起伏，每个点的法线都不同。
     * 渲染后可以清晰看到：
     *   · 法线朝向光源的区域 → 明亮（漫反射强）
     *   · 法线背离光源的区域 → 暗（漫反射弱）
     *   · 法线恰好反射到眼睛的区域 → 高光点
     *
     * 这就是文档所说的 Blinn-Phong 光照模型的直观体现。
     *
     * @param gridSize 网格细分数（每边 gridSize 个格子）
     * @param extent   平面半宽（从 -extent 到 +extent）
     * @param amplitude 波浪高度
     * @param frequency 波浪频率
     */
    fun generateWavySurface(
        gridSize: Int = 30,
        extent: Float = 0.8f,
        amplitude: Float = 0.10f,
        frequency: Float = 2.5f,
    ): MeshData {
        val positions = mutableListOf<Float>()
        val normals = mutableListOf<Float>()
        val freq = (frequency * Math.PI).toFloat()

        for (i in 0..gridSize) {
            for (j in 0..gridSize) {
                val x = -extent + 2f * extent * i / gridSize
                val z = -extent + 2f * extent * j / gridSize
                val y = amplitude * sin(x * freq) * cos(z * freq)
                positions.addAll(listOf(x, y, z))

                val dydx = amplitude * freq * cos(x * freq) * cos(z * freq)
                val dydz = -amplitude * freq * sin(x * freq) * sin(z * freq)
                val nx = -dydx; val ny = 1f; val nz = -dydz
                val l = sqrt(nx * nx + ny * ny + nz * nz)
                normals.addAll(listOf(nx / l, ny / l, nz / l))
            }
        }

        val indices = mutableListOf<Short>()
        val cols = gridSize + 1
        for (i in 0 until gridSize) {
            for (j in 0 until gridSize) {
                val a = (i * cols + j).toShort()
                val b = ((i + 1) * cols + j).toShort()
                val c = ((i + 1) * cols + j + 1).toShort()
                val d = (i * cols + j + 1).toShort()
                indices.addAll(listOf(a, b, c, a, c, d))
            }
        }

        return MeshData(positions.toFloatArray(), normals.toFloatArray(), indices.toShortArray())
    }
}
