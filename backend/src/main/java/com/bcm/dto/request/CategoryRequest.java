package com.bcm.dto.request;
import jakarta.validation.constraints.*;
import com.bcm.entity.*;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.UUID;
public record CategoryRequest(@NotBlank @Size(max=255) String categoryName) {}
