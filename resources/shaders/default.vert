#version 330 core

layout (location = 0) in vec3 vLocal;
layout (location = 1) in vec3 vColor;
layout (location = 2) in vec2 vTextCoords;

out vec3 vertexColor;
out vec2 textCoord;

uniform float t;
uniform float speed;

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
  textCoord = vTextCoords;
  gl_Position = projection * view * model * vec4(vLocal.x, // + sin(speed * t),
                                                 vLocal.y + tan(speed * t),
                                                 vLocal.z, 1.0); // + cos(speed * t), 1.0);
}
