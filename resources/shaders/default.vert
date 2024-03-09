#version 330 core

layout (location = 0) in vec3 vLocal;
layout (location = 1) in vec3 vColor;
layout (location = 2) in vec2 vTextCoords[3];

out vec3 vertexColor;
out vec2 textCoord;

uniform float t;
uniform float speed;

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
  textCoord = vTextCoords[animIndex];
  gl_Position = projection * view * model * vec4(vLocal.x + speed * cos(t),
                                                 vLocal.y + speed * sin(t),
                                                 vLocal.z, 1.0);  // + cos(2.0 / speed * t), 1.0);
}
