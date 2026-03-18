#version 300 es
precision mediump float;
// ═══════════════════════════════════════════════════════════
// 天空盒 · 片元着色器
//
// 对应文档：「天空盒 — 你转头看哪个方向都有背景」
//
// 使用立方体贴图(CubeMap)采样：
//   根据方向向量 vDir，GPU 自动选择 6 个面中的对应面，
//   并在该面上采样颜色。
// ═══════════════════════════════════════════════════════════

// 立方体贴图采样器 — 6 张图拼成的环境贴图
uniform samplerCube uSkybox;

// 采样方向（从顶点着色器传入的方向向量）
in vec3 vDir;

out vec4 fragColor;

void main() {
    // 用方向向量从 CubeMap 中采样天空颜色
    fragColor = texture(uSkybox, vDir);
}
