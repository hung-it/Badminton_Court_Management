package com.bcm.dto.request;

import jakarta.validation.constraints.*;

/** Self-service profile fields only; identity and credentials are not writable. */
public record UpdateProfileRequest(
        @NotBlank @Size(max=255) String fullName,
        @NotBlank @Pattern(regexp="[+0-9][0-9 .-]{7,19}") String phone,
        @Size(max=2000) String address) { }
