#version 300 es
precision highp float;
in float v_dist;
uniform vec4 u_color;
uniform float u_width;
out vec4 frag;
void main() {
    float a = clamp(u_width * 0.5 + 0.5 - abs(v_dist), 0.0, 1.0);
    if (a <= 0.0) discard;
    frag = vec4(u_color.rgb, u_color.a * a);
}
