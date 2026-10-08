package com.bcm.service;

import com.bcm.config.BookingPaymentConfig;
import com.bcm.entity.PaymentMethod;
import com.bcm.exception.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentGatewayPreparationTest {
    private final PaymentGatewayPreparation gateway = new PaymentGatewayPreparation(new BookingPaymentConfig());

    @ParameterizedTest
    @EnumSource(value = PaymentMethod.class, names = "VNPAY")
    void preparesInternalProviderAmountAndStableMerchantReference(PaymentMethod method) {
        UUID id = UUID.randomUUID();
        var amount = new BigDecimal("123456.78");
        var result = gateway.prepare(method, id, amount);
        assertThat(result.provider()).isEqualTo(method);
        assertThat(result.merchantReference()).isEqualTo(id.toString());
        assertThat(result.amount()).isEqualTo(amount);
        assertThat(result.checkoutReady()).isFalse();
        assertThat(gateway.prepare(method, id, amount)).isEqualTo(result);
    }

    @Test
    void rejectsMissingMethod() {
        assertThatThrownBy(() -> gateway.validateMethod(null)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void bindsConfiguredMethodsAndRejectsDisabledProvider() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
                .withUserConfiguration(BookingPaymentConfig.class, PaymentGatewayPreparation.class)
                .withPropertyValues("booking.payment.enabled-methods=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(BookingPaymentConfig.class).getEnabledMethods())
                            .isEmpty();
                    var configured = context.getBean(PaymentGatewayPreparation.class);
                    assertThatThrownBy(() -> configured.validateMethod(PaymentMethod.VNPAY))
                            .isInstanceOf(BadRequestException.class).hasMessageContaining("disabled");
                });
    }
}
