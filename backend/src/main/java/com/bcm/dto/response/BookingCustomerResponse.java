package com.bcm.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "Active customers.id resolved from the authenticated CUSTOMER principal; never users.id")
public record BookingCustomerResponse(UUID customerId) { }
