#version 300 es
// A picture laid on the map plane: one quad covering u_rect (x0, y0, x1, y1 in km).
layout(location = 0) in vec2 a_corner;   // 0..1, 0..1
uniform vec4 u_rect;
uniform vec2 u_center;
uniform float u_scale;
uniform vec2 u_vp;
out vec2 v_uv;
void main() {
    vec2 p = vec2(mix(u_rect.x, u_rect.z, a_corner.x), mix(u_rect.y, u_rect.w, a_corner.y));
    v_uv = vec2(a_corner.x, 1.0 - a_corner.y);     // texture row 0 is the northern edge
    gl_Position = vec4((p - u_center) * u_scale / (u_vp * 0.5), 0.0, 1.0);
}
