package com.bcm.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevenueReportResponse {

    private String period;
    private LocalDate from;
    private LocalDate to;

    @JsonProperty("court_amount")
    private BigDecimal courtAmount;

    @JsonProperty("product_amount")
    private BigDecimal productAmount;

    @JsonProperty("total_amount")
    private BigDecimal totalAmount;
}