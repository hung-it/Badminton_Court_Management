package com.bcm.service;

import com.bcm.dto.request.PosSaleItemRequest;
import com.bcm.dto.request.PosSaleRequest;
import com.bcm.entity.Customer;
import com.bcm.entity.Invoice;
import com.bcm.entity.InvoiceDetail;
import com.bcm.entity.Product;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.CustomerRepository;
import com.bcm.repository.InvoiceDetailRepository;
import com.bcm.repository.InvoiceRepository;
import com.bcm.repository.ProductRepository;
import jakarta.persistence.OptimisticLockException;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PosService {

    private static final String INSUFFICIENT_STOCK_MESSAGE = "Kho không đủ số lượng";

    private final ProductRepository productRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceDetailRepository invoiceDetailRepository;
    private final CustomerRepository customerRepository;

    @Transactional
    public Invoice sell(PosSaleRequest request) {
        Map<UUID, Integer> quantitiesByProduct = aggregateQuantities(request.getItems());
        List<InvoiceDetail> invoiceDetails = new ArrayList<>();
        BigDecimal productFee = BigDecimal.ZERO;

        try {
            for (Map.Entry<UUID, Integer> entry : quantitiesByProduct.entrySet()) {
                Product product = productRepository.findById(entry.getKey())
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
                int quantity = entry.getValue();

                if (product.getStockQuantity() < quantity) {
                    throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
                }

                product.setStockQuantity(product.getStockQuantity() - quantity);
                productRepository.saveAndFlush(product);

                BigDecimal lineAmount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
                productFee = productFee.add(lineAmount);
                invoiceDetails.add(InvoiceDetail.builder()
                        .productId(product.getId())
                        .quantity(quantity)
                        .unitPrice(product.getPrice())
                        .build());
            }
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
        }

        BigDecimal courtFee = request.getCourtFee() == null
                ? BigDecimal.ZERO
                : request.getCourtFee();
        String paymentMethod = request.getPaymentMethod() == null ? "CASH" : request.getPaymentMethod().trim().toUpperCase();
        if ("QR".equals(paymentMethod)) {
            paymentMethod = "BANK_TRANSFER";
        }
        if (!paymentMethod.equals("CASH") && !paymentMethod.equals("BANK_TRANSFER") && !paymentMethod.equals("VNPAY")) {
            throw new BadRequestException("Phương thức thanh toán phải là CASH, BANK_TRANSFER hoặc VNPAY");
        }
        UUID customerId = resolveCustomerId(request.getCustomerId());

        Invoice invoice = Invoice.builder()
                .bookingId(request.getBookingId())
                .customerId(customerId)
                .paymentMethod(paymentMethod)
                .status(request.getBookingId() == null ? "PAID" : "DRAFT")
                .courtFee(courtFee)
                .productFee(productFee)
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(courtFee.add(productFee))
                .createdBy(request.getStaffId())
                .updatedBy(request.getStaffId())
                .build();
        Invoice savedInvoice = invoiceRepository.saveAndFlush(invoice);

        invoiceDetails.forEach(detail -> detail.setInvoiceId(savedInvoice.getId()));
        invoiceDetailRepository.saveAll(invoiceDetails);
        return savedInvoice;
    }

    private Map<UUID, Integer> aggregateQuantities(List<PosSaleItemRequest> items) {
        Map<UUID, Integer> quantitiesByProduct = new LinkedHashMap<>();
        for (PosSaleItemRequest item : items) {
            quantitiesByProduct.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        return quantitiesByProduct;
    }

    private UUID resolveCustomerId(UUID requestedCustomerId) {
        if (requestedCustomerId != null && customerRepository.existsById(requestedCustomerId)) {
            return requestedCustomerId;
        }
        return customerRepository.findByPhone("0000000000")
                .map(Customer::getId)
                .or(() -> customerRepository.findAll().stream().findFirst().map(Customer::getId))
                .orElse(requestedCustomerId);
    }
}