package com.bcm.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Booking contact only. Email, account identity and other fields cannot be changed.")
public record UpdateBookingContactRequest(
        @NotBlank @Size(min = 2, max = 255) String fullName,
        @NotBlank @Pattern(regexp = "^[0-9]{10,20}$") String phone) {
    public UpdateBookingContactRequest {
        fullName = fullName == null ? null : fullName.trim();
        phone = phone == null ? null : phone.trim();
    }

    @JsonAnySetter
    public void rejectOtherFields(String field, Object ignored) {
        throw new IllegalArgumentException("Only fullName and phone may be updated");
    }
}
