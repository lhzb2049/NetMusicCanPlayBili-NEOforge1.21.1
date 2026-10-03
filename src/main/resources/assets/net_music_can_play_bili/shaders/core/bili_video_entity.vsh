#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out float sphericalVertexDistance;
out float cylindricalVertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec2 texCoord0;

void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    sphericalVertexDistance = length(view.xyz);
    cylindricalVertexDistance = length(view.xz);
    vertexColor = Color;
    lightMapColor = vec4(1.0);
    texCoord0 = UV0;
}