#version 330 core

out vec4 FragColor;

in vec3 vertexColor;
in vec2 textCoord;

uniform float t;
uniform float speed;
uniform sampler2D textIndex0;

void main()
{
    vec4 texColor = texture(textIndex0, textCoord);
    if(texColor.a < 0.1)
      discard;
    FragColor = texColor;

    // * vec4(vertexColor.r * (1.5 + cos(speed * t)),
    //       vertexColor.g * (2.0 + cos(speed * t)),
    //       vertexColor.b * (1.0 + cos(speed * t)), 1.0);
}
