package com.bcm.dto.request;

import com.bcm.entity.*;

import jakarta.validation.constraints.*;

public record CategoryRequest(@NotBlank @Size(max = 255) String categoryName) {}
