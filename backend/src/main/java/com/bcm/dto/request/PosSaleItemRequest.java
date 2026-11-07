package com.bcm.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PosSaleItemRequest {

    @NotNull
    private UUID productId;

    @NotNull
    @Positive
    private Integer quantity;
}