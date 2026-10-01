#version 300 es
// Anti-aliased lines of any width: one instanced quad per segment between
// consecutive points; a far-away separator point (x > 1e8) ends a line.
layout(location = 0) in vec2 a_p0;       // segment start (km), per instance
layout(location = 1) in vec2 a_p1;       // segment end (km), per instance
layout(location = 2) in vec2 a_corner;   // x: 0 = start, 1 = end; y: side -1 / +1
uniform vec2 u_center;
uniform float u_scale;
uniform vec2 u_vp;
uniform float u_width;                   // px
out float v_dist;
void main() {
    if (a_p0.x > 1.0e8 || a_p1.x > 1.0e8) {
        gl_Position = vec4(-2.0, -2.0, -2.0, 1.0);
        v_dist = 0.0;
        return;
    }
    vec2 s0 = (a_p0 - u_center) * u_scale;
    vec2 s1 = (a_p1 - u_center) * u_scale;
    vec2 d = s1 - s0;
    float len = length(d);
    vec2 dir = len > 1e-5 ? d / len : vec2(1.0, 0.0);
    vec2 nrm = vec2(-dir.y, dir.x);
    float hw = u_width * 0.5 + 1.0;
    vec2 p = mix(s0, s1, a_corner.x) + nrm * (a_corner.y * hw) + dir * ((a_corner.x * 2.0 - 1.0) * u_width * 0.5);
    v_dist = a_corner.y * hw;
    gl_Position = vec4(p / (u_vp * 0.5), 0.0, 1.0);
}
