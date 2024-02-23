#version 330 core

out vec4 FragColor;

in vec3 vertexColor;

uniform float t;
uniform float speed;

void main()
{
    FragColor = vec4(vertexColor.r * sin(speed * t), vertexColor.g * tan(speed * t), vertexColor.b * cos(speed * t), 1.0);
}
