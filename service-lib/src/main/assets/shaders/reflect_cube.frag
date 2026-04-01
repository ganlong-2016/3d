#version 300 es
precision mediump float;
// ═══════════════════════════════════════════════════════════
// 反射正方体 · 片元着色器
//
// 对应文档：「天空盒的隐藏作用 — 环境反射」
//
// 环境反射原理（三步）：
//   ① 算入射方向 I = normalize(表面点 - 摄像机位置)
//   ② 用 reflect(I, N) 算镜面反射方向 R
//   ③ 用反射方向 R 从天空盒 CubeMap 采样颜色
//
// 这就是为什么 3D 车漆能「倒映」周围环境
// ═══════════════════════════════════════════════════════════

uniform samplerCube uSkybox;   // 天空盒立方体贴图
uniform vec3 uCamPos;          // 摄像机世界空间位置

in vec3 vWorldPos;
in vec3 vNormal;

out vec4 fragColor;

void main() {
    // ① 入射方向：从摄像机指向表面点
    vec3 I = normalize(vWorldPos - uCamPos);

    // ② 反射方向：入射光关于法线的镜面反射
    vec3 R = reflect(I, normalize(vNormal));

    // ③ 用反射方向从天空盒采样环境颜色
    vec4 env = texture(uSkybox, R);

    // 混合金属底色(35%)和环境反射(65%)
    // 模拟金属车漆 — 既有自身色泽又能反射环境
    vec3 tint = vec3(0.72, 0.78, 0.85);
    fragColor = vec4(mix(tint, env.rgb, 0.65), 1.0);
}
