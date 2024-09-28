#version 330 core

layout (location = 0) in vec3 vLocal;
layout (location = 1) in vec3 vColor;

out vec3 vertexColor;

void main()
{
  vertexColor = vColor;
  gl_Position = vec4(vLocal, 1.0);
}
