package com.bcm.dto.request;
import jakarta.validation.constraints.*;
public record AdminCustomerRequest(@NotBlank @Size(max=255) String fullName,
 @NotBlank @Email @Size(max=255) String email,
 @NotBlank @Pattern(regexp="[+0-9][0-9 .-]{7,19}") String phone,
 @Size(max=2000) String address, @Size(min=8,max=72) String password) {}
