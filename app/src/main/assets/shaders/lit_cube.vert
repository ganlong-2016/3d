#version 300 es
// ═══════════════════════════════════════════════════════════
// 光照正方体 · 顶点着色器
//
// 对应文档：「Shader — 结合灯光环境，逐像素上色」
//
// 除了 MVP 变换，还将顶点位置和法线变换到世界空间，
// 为片元着色器的光照计算做准备。
//
// 法线(Normal)的作用：
//   法线是表面朝向的方向向量。
//   光照计算需要知道表面朝哪个方向，才能算出明暗。
//   就像阳光直射的面最亮，侧面稍暗，背面最暗。
// ═══════════════════════════════════════════════════════════

uniform mat4 uMVP;     // MVP 变换矩阵（投影到屏幕）
uniform mat4 uModel;   // 模型矩阵（将法线变换到世界空间）

layout(location = 0) in vec3 aPos;     // 模型空间顶点位置
layout(location = 1) in vec3 aNormal;  // 模型空间法线方向

// 传递到片元着色器的世界空间数据
out vec3 vWorldPos;     // 世界空间顶点位置（用于算视线方向）
out vec3 vWorldNormal;  // 世界空间法线（用于算光照强度）

void main() {
    // 将顶点位置变换到世界空间
    vec4 wp = uModel * vec4(aPos, 1.0);
    vWorldPos = wp.xyz;

    // 将法线变换到世界空间
    // 用 mat3(uModel) 提取旋转部分，忽略平移
    vWorldNormal = mat3(uModel) * aNormal;

    // MVP 变换输出屏幕坐标
    gl_Position = uMVP * vec4(aPos, 1.0);
}
