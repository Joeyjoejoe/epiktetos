#version 330 core

layout (location = 0) in vec3 vLocal;
layout (location = 1) in vec3 vColor;
layout (location = 2) in vec3 instancePosition;

out vec4 vertexColor;

float scale = 0.05;

void main()
{
  vertexColor = vec4(vColor, 1.0);
  gl_Position = vec4(vLocal * scale + instancePosition, 1.0);
}
