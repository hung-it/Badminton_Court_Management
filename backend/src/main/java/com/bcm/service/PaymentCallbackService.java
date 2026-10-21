package com.bcm.service;

import com.bcm.dto.response.VnPayIpnAcknowledgment;
import com.bcm.exception.PaymentNotificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.sql.SQLException;
import static com.bcm.service.PaymentCallbackTransactionService.Result;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentCallbackService {
    private final VnPayIpnVerifier vnpay;
    private final PaymentCallbackTransactionService transactions;

    @Transactional(propagation = Propagation.NEVER)
    public VnPayIpnAcknowledgment vnpay(Map<String, String> parameters) {
        try {
            return switch (handle(vnpay.verify(parameters))) {
                case SUCCESS, IGNORED -> VnPayIpnAcknowledgment.of("00");
                case ALREADY_CONFIRMED -> VnPayIpnAcknowledgment.of("02");
                case NOT_FOUND -> VnPayIpnAcknowledgment.of("01");
                case INVALID_AMOUNT -> VnPayIpnAcknowledgment.of("04");
                case LATE_EXPIRED -> new VnPayIpnAcknowledgment("99", "Booking expired or payment deadline passed");
                case NOT_PAYABLE -> new VnPayIpnAcknowledgment("99", "Booking or payment is not payable");
                case CONFLICT -> new VnPayIpnAcknowledgment("99", "Conflicting payment confirmation");
                case TRANSACTION_ID_CONFLICT -> new VnPayIpnAcknowledgment("99", "Transaction ID belongs to another payment");
                default -> VnPayIpnAcknowledgment.of("99");
            };
        } catch (PaymentNotificationException ex) {
            return VnPayIpnAcknowledgment.of(switch (ex.getReason()) {
                case INVALID_SIGNATURE -> "97";
                case UNKNOWN_REFERENCE -> "01";
                default -> "99";
            });
        } catch (RuntimeException ex) {
            // Includes flush/commit failures from the transactional proxy. Never acknowledge success before commit.
            return VnPayIpnAcknowledgment.of("99");
        }
    }

    private PaymentCallbackTransactionService.Result handle(PaymentNotification notification) {
        var resolution = transactions.resolve(notification);
        Result result = resolution.rejection();
        if (result == null) {
            if (!notification.providerSuccess()) { result = Result.IGNORED; }
            else {
                try {
                    result = transactions.settle(notification, resolution.bookingId()); // commit before transport mapping
                } catch (DataIntegrityViolationException ex) {
                    // The proxy has already rolled back; never continue in a failed persistence context.
                    if (!transactionIdCollision(ex)) { throw ex; }
                    result = Result.TRANSACTION_ID_CONFLICT;
                }
            }
        }
        if (result != Result.SUCCESS && result != Result.ALREADY_CONFIRMED && result != Result.IGNORED) {
            // Existing logging only: no payload/credentials, new status, note overload or reconciliation storage.
            log.warn("Payment callback outcome {} for provider {} attempt {}", result, notification.method(), notification.attemptId());
        }
        return result;
    }

    private boolean transactionIdCollision(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint
                    && "23505".equals(constraint.getSQLState())
                    && "uq_transaction_id".equals(constraint.getConstraintName())) { return true; }
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null && sql.getMessage().contains("\"uq_transaction_id\"")) { return true; }
        }
        return false;
    }
}
