package com.bcm.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record StatisticsResponse(String period, List<Row> rows, BigDecimal totalRevenue,
                                 BigDecimal totalExpense, BigDecimal totalProfit) {
    public record Row(String label, LocalDate from, LocalDate to, BigDecimal revenue,
                      BigDecimal expense, BigDecimal profit) {}
}
