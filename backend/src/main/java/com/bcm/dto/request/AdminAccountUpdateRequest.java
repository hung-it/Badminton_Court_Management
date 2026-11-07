package com.bcm.dto.request;
import jakarta.validation.constraints.*;
/** Profile-only DTO: no password, role or identity mutation. */
public record AdminAccountUpdateRequest(@NotBlank @Size(max=255) String fullName,
 @NotBlank @Email @Size(max=255) String email,
 @NotBlank @Pattern(regexp="[+0-9][0-9 .-]{7,19}") String phone,@Size(max=2000) String address) {}
