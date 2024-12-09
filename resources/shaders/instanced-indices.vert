#version 330 core

layout (location = 0) in vec3 vLocal;
layout (location = 1) in vec3 vColor;
layout (location = 2) in vec3 normals;
layout (location = 3) in vec4 instancePosition;
layout (location = 4) in vec3 instanceColor;
layout (location = 5) in vec3 instanceSpeed;

out vec3 vertexColor;

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
  vertexColor = vColor * instanceColor;
  vec4 instanceVertexPos = vec4(vLocal, 1.0) + instancePosition;
  gl_Position = projection * view * model * vec4(instanceVertexPos.x + sin(t) * instanceSpeed.x,
                                                 instanceVertexPos.y + cos(t) * instanceSpeed.y,
                                                 instanceVertexPos.z + cos(t) * instanceSpeed.z, 1.0);
}

