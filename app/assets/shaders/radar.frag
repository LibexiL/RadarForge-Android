#version 300 es
precision highp float;
precision highp int;
precision highp usampler2D;
in vec2 v_pos;
flat in int v_row;
flat in vec2 v_bounds;
uniform usampler2D u_data;     // raw gate codes (R8UI / R16UI), row = ray sorted by azimuth
uniform sampler2D u_lut;       // colour table, 1024 x 1
uniform float u_first;         // centre of the first gate (km)
uniform float u_spacing;       // gate spacing (km)
uniform float u_elev;          // elevation (radians)
uniform int u_ngates;
uniform int u_nrays;
uniform float u_cscale;        // value = (code - u_coffset) / u_cscale
uniform float u_coffset;
uniform float u_dscale;        // table value = value * u_dscale + u_doffset
uniform float u_doffset;
uniform float u_lutmin;
uniform float u_lutmax;
uniform vec4 u_rf;             // range-folded colour
uniform int u_smooth;
uniform float u_nyq;           // > 0: velocity; unfold neighbours before blending
uniform vec2 u_storm;          // storm motion (u, v m/s) removed for SRV
out vec4 frag;

const float AE = 8494.6667;    // 4/3 earth radius (km)

float slantRange(float s) {
    float phi = s / AE;
    return AE * sin(phi) / cos(u_elev + phi);
}

float decode(uint c) { return (float(c) - u_coffset) / u_cscale; }

float unfold(float n, float c) {
    if (u_nyq <= 0.0) return n;
    float iv = 2.0 * u_nyq;
    return n - iv * floor((n - c) / iv + 0.5);
}

void main() {
    float s = length(v_pos);
    float r = slantRange(s);
    float gf = (r - (u_first - 0.5 * u_spacing)) / u_spacing;
    if (gf < 0.0 || gf >= float(u_ngates)) discard;
    int g = int(gf);
    uint c = texelFetch(u_data, ivec2(g, v_row), 0).r;
    if (c == 0u) discard;
    if (c == 1u) { frag = u_rf; return; }
    float v = decode(c);
    if (u_smooth == 1) {
        float tg = gf - 0.5 - float(g);
        int g2 = clamp(tg < 0.0 ? g - 1 : g + 1, 0, u_ngates - 1);
        float wg = abs(tg);
        float az = degrees(atan(v_pos.x, v_pos.y));
        float span = mod(v_bounds.y - v_bounds.x + 360.0, 360.0);
        float fa = mod(az - v_bounds.x + 720.0, 360.0) / max(span, 1e-4) - 0.5;
        int r2 = fa < 0.0 ? v_row - 1 : v_row + 1;
        r2 = (r2 + u_nrays) % u_nrays;
        float wa = min(abs(fa), 0.5);
        uint c10 = texelFetch(u_data, ivec2(g2, v_row), 0).r;
        uint c01 = texelFetch(u_data, ivec2(g, r2), 0).r;
        uint c11 = texelFetch(u_data, ivec2(g2, r2), 0).r;
        float w00 = (1.0 - wg) * (1.0 - wa);
        float w10 = wg * (1.0 - wa);
        float w01 = (1.0 - wg) * wa;
        float w11 = wg * wa;
        float acc = v * w00;
        float wsum = w00;
        if (c10 > 1u) { acc += unfold(decode(c10), v) * w10; wsum += w10; }
        if (c01 > 1u) { acc += unfold(decode(c01), v) * w01; wsum += w01; }
        if (c11 > 1u) { acc += unfold(decode(c11), v) * w11; wsum += w11; }
        v = acc / wsum;
    }
    float azc = radians(0.5 * (v_bounds.x + v_bounds.y));
    v -= u_storm.x * sin(azc) + u_storm.y * cos(azc);
    float d = v * u_dscale + u_doffset;
    float t = (d - u_lutmin) / (u_lutmax - u_lutmin);
    if (t < 0.0) discard;
    vec4 col = texture(u_lut, vec2(clamp(t, 0.0, 0.9995), 0.5));
    if (col.a < 0.01) discard;
    frag = col;
}
