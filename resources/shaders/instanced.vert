#version 330 core

layout (location = 0) in vec3 vLocal;
layout (location = 1) in vec3 vColor;
layout (location = 2) in vec2 vertexTexCoords;
layout (location = 3) in vec4 instancePosition;
layout (location = 4) in vec4 instanceColor;
layout (location = 5) in vec4 instanceSpeed;

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

mat4 rotationMatrix(vec3 axis, float angle) {
    axis = normalize(axis);
    float s = sin(angle);
    float c = cos(angle);
    float oc = 1.0 - c;

    return mat4(oc * axis.x * axis.x + c,           oc * axis.x * axis.y - axis.z * s,  oc * axis.z * axis.x + axis.y * s,  0.0,
                oc * axis.x * axis.y + axis.z * s,  oc * axis.y * axis.y + c,           oc * axis.y * axis.z - axis.x * s,  0.0,
                oc * axis.z * axis.x - axis.y * s,  oc * axis.y * axis.z + axis.x * s,  oc * axis.z * axis.z + c,           0.0,
                0.0,                                0.0,                                0.0,                                1.0);
}

void main()
{
  vertexColor = instanceColor.rgb;
  textCoord = vertexTexCoords;
  ;

  vec4 instanceVertexPos = rotationMatrix(instancePosition.xyz, t * instanceSpeed.x) * (vec4(vLocal, 1.0) + instancePosition);
  gl_Position = projection * view * model * vec4(instanceVertexPos.x + sin(t) * instanceSpeed.x,
                                                 instanceVertexPos.y + cos(t) * instanceSpeed.y,
                                                 instanceVertexPos.z + cos(t) * instanceSpeed.z, 1.0);
}
