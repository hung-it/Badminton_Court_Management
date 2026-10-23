package com.bcm.dto.request;

import com.bcm.entity.PaymentMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreatePaymentAttemptRequest {
    @NotNull
    @Schema(type = "string", allowableValues = {"VNPAY", "BANK_TRANSFER"}, description = "CASH is forbidden for booking payments")
    private PaymentMethod paymentMethod;

    @Valid
    private BankTransfer bankTransfer;

    public CreatePaymentAttemptRequest(PaymentMethod paymentMethod) {
        this.paymentMethod = paymentMethod;
    }

    /** Payer-supplied information, never evidence of receipt or recipient account configuration. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankTransfer {
        @NotBlank @Size(max = 100)
        private String bankName;
        @NotBlank @Size(max = 150)
        private String accountHolder;
        @NotBlank @Pattern(regexp = "[0-9]{1,34}")
        private String accountNumber;
        @NotBlank @Size(max = 255)
        private String transferContent;
    }

    // Avoid request DEBUG logging of bank account information.
    @Override public String toString() { return "CreatePaymentAttemptRequest[paymentMethod=" + paymentMethod + "]"; }
}
