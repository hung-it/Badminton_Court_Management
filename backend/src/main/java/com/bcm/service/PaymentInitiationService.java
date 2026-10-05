package com.bcm.service;

import com.bcm.dto.request.CreatePaymentAttemptRequest;
import com.bcm.dto.response.PaymentAttemptResponse;
import com.bcm.entity.PaymentMethod;
import com.bcm.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PaymentInitiationService {
    private final PaymentAttemptService attempts;
    private final PaymentGatewayPreparation preparation;
    private final VnPayGateway vnpay;
    private final MoMoGateway momo;

    @Transactional(propagation = Propagation.NEVER)
    public PaymentAttemptResponse initiate(UUID bookingId, CreatePaymentAttemptRequest request, String remoteAddress) {
        if (request == null) { throw new BadRequestException("Payment method is required"); }
        preparation.validateMethod(request.getPaymentMethod());
        boolean isMomo = request.getPaymentMethod() == PaymentMethod.MOMO;
        if (isMomo) { momo.validateConfiguration(); } else { vnpay.validateConfiguration(); }
        var created = attempts.createOrReuseAttempt(bookingId, request); // transaction commits here, via proxy
        var checked = attempts.recheckForInitiation(created.paymentAttemptId()); // short lock, released before provider work
        PaymentAttemptResponse.GatewayPreparation checkout;
        if (isMomo) {
            momo.initiate(checked); // HTTP after both service transactions have committed/released locks
            checkout = new PaymentAttemptResponse.GatewayPreparation(PaymentMethod.MOMO,
                    checked.paymentAttemptId().toString(), checked.amount(), false, null, null, null,
                    MoMoGateway.CHECKOUT_BLOCKER);
        } else {
            checkout = vnpay.initiate(checked, remoteAddress);
        }
        var current = attempts.recheckForInitiation(checked.paymentAttemptId()); // suppress checkout if deadline/state changed
        return new PaymentAttemptResponse(current.paymentAttemptId(), current.bookingId(), current.paymentMethod(),
                current.status(), current.amount(), current.bookingExpiresAt(), checkout);
    }
}
