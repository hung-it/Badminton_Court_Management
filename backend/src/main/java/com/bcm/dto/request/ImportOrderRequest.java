package com.bcm.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImportOrderRequest {

    @NotNull
    private UUID supplierId;

    @NotNull
    private LocalDate importDate;

    private UUID staffId;

    @Valid
    @NotEmpty
    private List<ImportOrderDetailRequest> details;
}