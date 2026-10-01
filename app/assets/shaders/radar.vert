#version 300 es
// One triangle per ray: radar origin + the far edge of the ray's wedge.
layout(location = 0) in vec2 a_pos;      // km from the radar (map plane)
layout(location = 1) in float a_row;     // texture row of this ray
layout(location = 2) in vec2 a_bounds;   // azimuth lo / hi of the ray (deg)
uniform vec2 u_center;                   // view centre (km)
uniform float u_scale;                   // pixels per km
uniform vec2 u_vp;                       // viewport size (px)
out vec2 v_pos;
flat out int v_row;
flat out vec2 v_bounds;
void main() {
    v_pos = a_pos;
    v_row = int(a_row + 0.5);
    v_bounds = a_bounds;
    vec2 p = (a_pos - u_center) * u_scale;
    gl_Position = vec4(p / (u_vp * 0.5), 0.0, 1.0);
}
