package com.bcm.service;

import com.bcm.dto.request.PosSaleItemRequest;
import com.bcm.dto.request.PosSaleRequest;
import com.bcm.entity.Invoice;
import com.bcm.entity.InvoiceDetail;
import com.bcm.entity.Product;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
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

    @Transactional
    public Invoice sell(PosSaleRequest request) {
        Map<UUID, Integer> quantitiesByProduct = aggregateQuantities(request.getItems());
        List<InvoiceDetail> invoiceDetails = new ArrayList<>();
        BigDecimal productAmount = BigDecimal.ZERO;

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
                productAmount = productAmount.add(lineAmount);
                invoiceDetails.add(InvoiceDetail.builder()
                        .productId(product.getId())
                        .quantity(quantity)
                        .unitPrice(product.getPrice())
                        .build());
            }
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
        }

        BigDecimal courtAmount = request.getCourtAmount() == null
                ? BigDecimal.ZERO
            : request.getCourtAmount();
        Invoice invoice = Invoice.builder()
                .bookingId(request.getBookingId())
                .customerId(request.getCustomerId())
                .paymentMethod(request.getPaymentMethod())
                .status(request.getBookingId() == null ? "PAID" : "DRAFT")
                .courtAmount(courtAmount)
                .productAmount(productAmount)
                .discountAmount(BigDecimal.ZERO)
                .totalAmount(courtAmount.add(productAmount))
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
}