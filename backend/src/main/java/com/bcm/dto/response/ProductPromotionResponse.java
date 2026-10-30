package com.bcm.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ProductPromotionResponse(UUID id, String name, UUID productId, String productName,
        int minQuantity, BigDecimal discountPercent, LocalDate validFrom, LocalDate validTo,
        boolean active, String code) {}
