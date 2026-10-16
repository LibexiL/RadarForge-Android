#version 300 es
precision mediump float;
in vec2 v_uv;
uniform sampler2D u_tex;
uniform float u_alpha;
out vec4 frag;
void main() {
    vec4 c = texture(u_tex, v_uv);          // premultiplied alpha (so smoothing doesn't fringe)
    if (c.a < 0.004) discard;
    frag = vec4(c.rgb / c.a, c.a * u_alpha);
}
