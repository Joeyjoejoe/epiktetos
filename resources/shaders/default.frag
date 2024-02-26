#version 330 core

out vec4 FragColor;

in vec3 vertexColor;
in vec2 textCoord;

uniform float t;
uniform float speed;
uniform sampler2D textIndex0;

void main()
{
    FragColor = texture(textIndex0, textCoord) * vec4(vertexColor.r * (1.5 + cos(speed * t)),
                                                    vertexColor.g * (1.5 + cos(speed * t)),
                                                    vertexColor.b * (1.5 + cos(speed * t)), 1.0);
}
