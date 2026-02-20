#version 430 core

in vec3 vLocal;
in vec3 vColor;

out vec4 vertexColor;

const float scale = 0.4;


void main()
{
  vertexColor = vec4(vColor, 1.0);
  gl_Position = vec4(vLocal * scale, 1.0);
}
