// Simple uniforms
uniform mat4 view;

// Uniform with default value
uniform vec3 initialUniform = vec3(1.0, 0.0, 0.0);

// Uniform with manual location
layout(location = 2) uniform mat4 some_mats[10];

// struct uniform
struct TheStruct
{
  vec3 first;
  vec4 second;
  mat4x3 third;
};

uniform TheStruct aUniformOfArrayType;
// => :aUniformOfArrayType [vec3 vec4 mat4x3]
uniform TheStruct uniformArrayOfStructs[10];
// => :uniformArrayOfStructs [
  [vec3 vec4 mat4x3]
  [vec3 vec4 mat4x3]
  x10...
]

// block uniform
layout(std140, binding = 0) uniform LightBlock
{
    vec4 position;
    vec4 direction;
    vec4 color;
} lights[8];

layout(std140, binding = 0) uniform LightBlock
{
    TheStruct lights[8];
};










#version 330 core

layout (location = 0) in vec3 aPos;
layout (location = 1) in vec3 aColor;
layout (location = 2) in vec2 aTexCoord;
layout (location = 3) in vec3 aNormal;
layout (location = 4) in mat4 aWorldPos;

out vec3 vertexColor;
out vec2 TexCoord;
out vec3 Normal;
out vec3 FragPos;

void main()
{
    mat4 worldPosTransformation = aWorldPos * positionTransformation;
    FragPos = vec3(worldPosTransformation * vec4(aPos, 1.0));
    vertexColor = aColor;
    TexCoord = aTexCoord;
    Normal = mat3(transpose(inverse(worldPosTransformation))) * aNormal;
    gl_Position = projection * view * vec4(FragPos, 1.0);
}

