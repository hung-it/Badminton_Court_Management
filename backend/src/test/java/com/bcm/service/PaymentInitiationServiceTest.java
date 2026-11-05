package com.bcm.service;
import com.bcm.dto.request.CreatePaymentAttemptRequest;
import com.bcm.entity.PaymentMethod;
import com.bcm.exception.BadRequestException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class PaymentInitiationServiceTest {
 @ParameterizedTest @EnumSource(value=PaymentMethod.class,names={"CASH"})
 void rejectsNonVnpayBeforeAnyPersistence(PaymentMethod method){
  var attempts=mock(PaymentAttemptService.class);var preparation=mock(PaymentGatewayPreparation.class);var gateway=mock(VnPayGateway.class);
  var request=new CreatePaymentAttemptRequest();request.setPaymentMethod(method);
  assertThatThrownBy(()->new PaymentInitiationService(attempts,preparation,gateway).initiate(java.util.UUID.randomUUID(),request,"127.0.0.1"))
   .isInstanceOf(BadRequestException.class).hasMessage("Customer booking payments only support VNPAY");
  verifyNoInteractions(attempts,preparation,gateway);
 }
}
