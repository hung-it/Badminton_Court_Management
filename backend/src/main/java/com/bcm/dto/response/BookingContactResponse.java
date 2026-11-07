package com.bcm.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record BookingContactResponse(String fullName, String phone,
        @Schema(accessMode = Schema.AccessMode.READ_ONLY, description = "Account email, never editable through checkout") String email) { }
