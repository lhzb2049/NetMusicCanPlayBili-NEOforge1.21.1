// 1.21.1 include shim for the NetMusicCanPlayBili YUV core shaders.
// The mod's yuv420p/nv12/yuv420p_textured_probe fragment shaders (kept verbatim
// from the 26.1.2 assets) import <minecraft:dynamictransforms.glsl>, which
// Minecraft 1.21.1 does not ship. This file supplies the symbols those shaders
// reference: the ColorModulator uniform and the extended fog apply helper.
// The video surfaces are full-bright billboards, so apply_fog keeps the color
// unchanged; the declaration exists purely to satisfy the linker.

uniform vec4 ColorModulator;

uniform float FogEnvironmentalStart;
uniform float FogEnvironmentalEnd;
uniform float FogRenderDistanceStart;
uniform float FogRenderDistanceEnd;
uniform vec4 FogColor;

vec4 apply_fog(vec4 color, float sphericalVertexDistance, float cylindricalVertexDistance,
        float FogEnvironmentalStart, float FogEnvironmentalEnd,
        float FogRenderDistanceStart, float FogRenderDistanceEnd, vec4 FogColor) {
    return color;
}