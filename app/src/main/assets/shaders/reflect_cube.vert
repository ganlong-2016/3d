#version 300 es
// ═══════════════════════════════════════════════════════════
// 反射正方体 · 顶点着色器
//
// 对应文档：「车漆表面反射出的周围环境，其实就是天空盒图像」
//
// 为环境反射做准备：
//   将顶点位置和法线传递给片元着色器，
//   片元着色器用它们计算反射方向，再从天空盒采样。
// ═══════════════════════════════════════════════════════════

uniform mat4 uMVP;

layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;

out vec3 vWorldPos;    // 世界空间位置
out vec3 vNormal;      // 世界空间法线

void main() {
    // 模型矩阵为单位矩阵，世界坐标 = 模型坐标
    vWorldPos = aPos;
    vNormal = aNormal;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
