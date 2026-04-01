#version 300 es
precision mediump float;
// ═══════════════════════════════════════════════════════════
// 光照正方体 · 片元着色器
//
// 对应文档：
//   「灯光 — 展厅里的灯，没灯就全黑」
//   「材质 — 一套完整的表面工艺方案」
//   「Shader — 真正干活的喷漆机器人」
//
// 实现 Blinn-Phong 光照模型，包含三个分量：
//   ① 环境光(Ambient)  — 展厅基础照明，防止暗处全黑
//   ② 漫反射(Diffuse)  — 光线打到表面的明暗变化
//   ③ 镜面反射(Specular) — 高光点，让表面有光泽感
// ═══════════════════════════════════════════════════════════

// ─── 灯光参数（对应文档「展厅的射灯」）───
uniform vec3 uLightDir;    // 光照方向（指向光源）
uniform vec3 uLightColor;  // 光的颜色（暖白色模拟展厅灯光）

// ─── 材质参数（对应文档「喷漆工艺方案」）───
uniform vec3 uAmbient;     // 环境光颜色（基础照明）
uniform vec3 uObjColor;    // 物体底色（相当于车漆颜色）
uniform float uShininess;  // 高光锐利度（值越大高光越集中，越像金属漆）

// ─── 摄像机 ───
uniform vec3 uCamPos;      // 摄像机位置（用于计算视线方向）

in vec3 vWorldPos;
in vec3 vWorldNormal;

out vec4 fragColor;

void main() {
    // 标准化方向向量
    vec3 N = normalize(vWorldNormal);            // 表面朝向
    vec3 L = normalize(uLightDir);               // 光线方向（指向光源）
    vec3 V = normalize(uCamPos - vWorldPos);     // 视线方向（指向摄像机）
    vec3 H = normalize(L + V);                   // 半角向量（Blinn-Phong 优化）

    // ① 环境光 — 模拟展厅基础照明
    // 文档：「环境光 — 均匀照亮所有角落，防止暗处全黑」
    vec3 ambient = uAmbient * uObjColor;

    // ② 漫反射 — 朗伯余弦定律：表面法线与光线方向夹角越小越亮
    // 文档：「车身上的明暗变化 — 让车身有立体的光影弧线」
    float diff = max(dot(N, L), 0.0);
    vec3 diffuse = diff * uLightColor * uObjColor;

    // ③ 镜面反射 — 半角向量与法线越接近，高光越强
    // 文档：「车漆上的高光点 — 让你觉得漆面很亮很光滑」
    float spec = pow(max(dot(N, H), 0.0), uShininess);
    vec3 specular = spec * uLightColor;

    // 三个分量叠加 = 最终像素颜色
    fragColor = vec4(ambient + diffuse + specular, 1.0);
}
