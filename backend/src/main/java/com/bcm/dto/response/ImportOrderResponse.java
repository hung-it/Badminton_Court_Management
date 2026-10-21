package com.bcm.dto.response;

import com.bcm.entity.ImportOrder;
import com.bcm.entity.ImportOrderDetail;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ImportOrderResponse {

    private UUID id;
    private UUID supplierId;
    private String status;
    private BigDecimal totalAmount;
    private LocalDate importDate;
    private UUID createdBy;
    private UUID updatedBy;
    private List<ImportOrderDetail> details;

    public static ImportOrderResponse from(ImportOrder order, List<ImportOrderDetail> details) {
        return ImportOrderResponse.builder()
                .id(order.getId())
                .supplierId(order.getSupplierId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .importDate(order.getImportDate())
                .createdBy(order.getCreatedBy())
                .updatedBy(order.getUpdatedBy())
                .details(details)
                .build();
    }
}