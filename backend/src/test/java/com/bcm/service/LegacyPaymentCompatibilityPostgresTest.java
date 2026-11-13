package com.bcm.service;
import com.bcm.entity.PaymentMethod;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class LegacyPaymentCompatibilityPostgresTest {
 @Test void onlySupportedMethodsExist(){
  assertThat(PaymentMethod.values()).extracting(Enum::name).containsExactly("CASH","VNPAY");
  assertThatThrownBy(()->PaymentMethod.valueOf("BANK_TRANSFER")).isInstanceOf(IllegalArgumentException.class);
 }
}
