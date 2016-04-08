//--------------------------------------------------------------------------------------
// Order Independent Transparency with Dual Depth Peeling
//
// Author: Louis Bavoil
// Email: sdkfeedback@nvidia.com
//
// Copyright (c) NVIDIA Corporation. All rights reserved.
//--------------------------------------------------------------------------------------


#version 330

layout (location = 0) out vec2 outputColor;

void main(void)
{
    outputColor = vec2(-gl_FragCoord.z, gl_FragCoord.z);
}