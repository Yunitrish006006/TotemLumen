#version 330
#extension GL_ARB_separate_shader_objects : require
uniform sampler2D DepthSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    fragColor = vec4(depth, 0.0, 0.0, depth > 0.000001 ? 1.0 : 0.0);
}
