package com.bcm.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "Zero-based page of booking headers, ordered by createdAt DESC then bookingId DESC")
public record BookingHistoryResponse(List<BookingSummaryResponse> content, int page, int size,
                                     long totalElements, int totalPages) { }
