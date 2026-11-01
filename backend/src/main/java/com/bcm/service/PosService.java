package com.bcm.service;

import com.bcm.dto.request.PosSaleItemRequest;
import com.bcm.dto.request.PosSaleRequest;
import com.bcm.dto.response.PosQuoteResponse;
import com.bcm.entity.ProductPromotionRule;
import com.bcm.entity.Customer;
import com.bcm.entity.Invoice;
import com.bcm.entity.InvoiceDetail;
import com.bcm.entity.Product;
import com.bcm.entity.ProductType;
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
    private final ProductPromotionService productPromotions;
    private final ProductPriceService productPrices;
    private final com.bcm.repository.BookingRepository bookings;

    @Transactional
    public Invoice sell(PosSaleRequest request) {
        validateBooking(request);
        Map<UUID, Integer> quantitiesByProduct = aggregateQuantities(request.getItems());
        var enteredCode=productPromotions.validatePosCode(request.getPromotionCode(),quantitiesByProduct);
        List<InvoiceDetail> invoiceDetails = new ArrayList<>();
        BigDecimal productFee = BigDecimal.ZERO;
        BigDecimal automaticDiscount = BigDecimal.ZERO;
        List<ProductPromotionRule> candidates = productPromotions.applicable();

        try {
            for (Map.Entry<UUID, Integer> entry : quantitiesByProduct.entrySet()) {
                Product product = productRepository.findById(entry.getKey())
                        .filter(p -> p.getDeletedAt() == null)
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
                productPrices.requireApproved(product);
                int quantity = entry.getValue();

                // Only GOODS products have physical inventory; SERVICE products are non-stocked
                if (product.getType() == ProductType.GOODS) {
                    if (product.getStockQuantity() < quantity) {
                        throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
                    }
                    product.setStockQuantity(product.getStockQuantity() - quantity);
                    productRepository.saveAndFlush(product);
                }

                BigDecimal lineAmount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
                productFee = productFee.add(lineAmount);
                var discount = productPromotions.best(product, quantity, candidates,enteredCode);
                automaticDiscount = automaticDiscount.add(discount.amount());
                invoiceDetails.add(InvoiceDetail.builder()
                        .productId(product.getId())
                        .quantity(quantity)
                        .unitPrice(product.getPrice())
                        .discountAmount(discount.amount()).promotionId(discount.promotionId())
                        .promotionName(discount.name()).discountPercent(discount.percent())
                        .build());
            }
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
        }

        BigDecimal courtFee = request.getCourtFee() == null
                ? BigDecimal.ZERO
                : request.getCourtFee();
        BigDecimal discountAmount = request.getDiscountAmount() == null
                ? BigDecimal.ZERO
                : request.getDiscountAmount();
        if (discountAmount.signum() < 0) {
            throw new BadRequestException("Discount amount cannot be negative");
        }
        if (courtFee.signum() < 0) throw new BadRequestException("Tiền sân không được âm");
        if (discountAmount.signum() > 0 && automaticDiscount.signum() > 0)
            throw new BadRequestException("Không cộng dồn giảm giá khác với khuyến mãi sản phẩm");
        discountAmount = discountAmount.add(automaticDiscount);
        if (discountAmount.compareTo(courtFee.add(productFee)) > 0)
            throw new BadRequestException("Giảm giá không được vượt giá trị hóa đơn");
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
                .discountAmount(discountAmount)
                .totalAmount(courtFee.add(productFee).subtract(discountAmount))
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
            try {
                quantitiesByProduct.merge(item.getProductId(), item.getQuantity(), Math::addExact);
            } catch (ArithmeticException ex) { throw new BadRequestException("Số lượng vượt giới hạn cho phép"); }
        }
        return quantitiesByProduct;
    }

    @org.springframework.transaction.annotation.Transactional
    public PosQuoteResponse quote(PosSaleRequest request) {
        validateBooking(request);
        var enteredCode=productPromotions.validatePosCode(request.getPromotionCode(),aggregateQuantities(request.getItems()));
        List<ProductPromotionRule> candidates = productPromotions.applicable();
        List<PosQuoteResponse.Line> lines = new ArrayList<>();
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        for (var entry : aggregateQuantities(request.getItems()).entrySet()) {
            Product product = productRepository.findByIdAndDeletedAtIsNull(entry.getKey())
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
            productPrices.requireApproved(product);
            int quantity = entry.getValue();
            if (product.getType() == ProductType.GOODS && product.getStockQuantity() < quantity)
                throw new BadRequestException(INSUFFICIENT_STOCK_MESSAGE);
            BigDecimal amount = product.getPrice().multiply(BigDecimal.valueOf(quantity));
            var discount = productPromotions.best(product, quantity, candidates,enteredCode);
            gross = gross.add(amount);
            discounts = discounts.add(discount.amount());
            lines.add(new PosQuoteResponse.Line(product.getId(), product.getName(), quantity, product.getPrice(),
                    amount, discount.amount(), amount.subtract(discount.amount()), discount.promotionId(), discount.name(), discount.percent()));
        }
        BigDecimal court = request.getCourtFee() == null ? BigDecimal.ZERO : request.getCourtFee();
        BigDecimal manual = request.getDiscountAmount() == null ? BigDecimal.ZERO : request.getDiscountAmount();
        if (court.signum() < 0 || manual.signum() < 0) throw new BadRequestException("Số tiền không được âm");
        if (manual.signum() > 0 && discounts.signum() > 0)
            throw new BadRequestException("Không cộng dồn giảm giá khác với khuyến mãi sản phẩm");
        discounts = discounts.add(manual);
        if (discounts.compareTo(court.add(gross)) > 0) throw new BadRequestException("Giảm giá vượt giá trị hóa đơn");
        return new PosQuoteResponse(gross, court, discounts, court.add(gross).subtract(discounts), lines);
    }

    private void validateBooking(PosSaleRequest request) {
        if(request.getBookingId()==null) return;
        var b=bookings.findByIdForExpiration(request.getBookingId()).orElseThrow(()->new ResourceNotFoundException("Không tìm thấy booking"));
        if(b.getStatus()!=com.bcm.entity.BookingStatus.PAID && b.getStatus()!=com.bcm.entity.BookingStatus.CHECKED_IN) throw new BadRequestException("Chỉ thêm hàng cho booking đã thanh toán, chưa kết toán");
        if((request.getCourtFee()!=null && request.getCourtFee().signum()!=0) || (request.getDiscountAmount()!=null && request.getDiscountAmount().signum()!=0)) throw new BadRequestException("Tiền sân và ưu đãi sân đã chốt trên booking; không nhập lại tại POS");
        request.setCustomerId(b.getCustomer().getId());
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
