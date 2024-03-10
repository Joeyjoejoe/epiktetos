#version 330 core

layout (location = 0) in vec3 vLocal;
layout (location = 1) in vec3 vColor;
layout (location = 2) in vec2 animCoords[4];

out vec3 vertexColor;
out vec2 textCoord;

uniform int animIndex;

// https://learnopengl.com/Getting-started/Coordinate-Systems
// vertices tranformations across various coordinates spaces

// Entity :standing (local->world space)
uniform mat4 model;

// TODO :camera (world->view space)
uniform mat4 view;

// view->clip (set the frustum of visible items and perspective)
uniform mat4 projection;

void main()
{
  vertexColor = vColor;
  textCoord = animCoords[animIndex];
  gl_Position = projection * view * model * vec4(vLocal, 1.0);
}
