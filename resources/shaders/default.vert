#version 330 core

layout (location = 0) in vec3 vLocal;
layout (location = 1) in vec3 vColor;

out vec3 vertexColor;

// https://learnopengl.com/Getting-started/Coordinate-Systems
// vertices tranformnations across various coordinates spaces

// local->world space (transformation matrix of original position)
uniform mat4 model;

// world->view (transformation matrix to simulate camera)
uniform mat4 view;

// view->clip (set the frustum of visible items and perspective)
uniform mat4 projection;

void main()
{
  vertexColor = vColor;
  gl_Position = projection * view * model * vec4(vLocal, 1.0);
}
