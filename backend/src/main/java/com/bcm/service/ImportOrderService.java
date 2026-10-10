package com.bcm.service;

import com.bcm.dto.request.ImportOrderDetailRequest;
import com.bcm.dto.request.ImportOrderRequest;
import com.bcm.dto.response.ImportOrderResponse;
import com.bcm.entity.ImportOrder;
import com.bcm.entity.ImportOrderDetail;
import com.bcm.entity.Product;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.ResourceNotFoundException;
import com.bcm.repository.ImportOrderDetailRepository;
import com.bcm.repository.ImportOrderRepository;
import com.bcm.repository.ProductRepository;
import com.bcm.repository.SupplierRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ImportOrderService {

    private static final String DRAFT_STATUS = "DRAFT";
    private static final String CONFIRMED_STATUS = "CONFIRMED";
    private static final String RECEIVED_STATUS = "RECEIVED";

    private final ImportOrderRepository importOrderRepository;
    private final ImportOrderDetailRepository detailRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;

    public List<ImportOrderResponse> findAll() {
        return importOrderRepository.findAllByDeletedAtIsNullOrderByImportDateDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    public ImportOrderResponse findById(UUID id) {
        return toResponse(getOrder(id));
    }

    @Transactional
    public ImportOrderResponse create(ImportOrderRequest request) {
        requireSupplier(request.getSupplierId());
        ImportOrder order = ImportOrder.builder()
                .supplierId(request.getSupplierId())
                .status(DRAFT_STATUS)
                .totalAmount(calculateTotal(request.getDetails()))
                .importDate(request.getImportDate())
                .createdBy(request.getStaffId())
                .updatedBy(request.getStaffId())
                .build();
        ImportOrder savedOrder = importOrderRepository.saveAndFlush(order);
        saveDetails(savedOrder.getId(), request.getDetails());
        return toResponse(savedOrder);
    }

    @Transactional
    public ImportOrderResponse update(UUID id, ImportOrderRequest request) {
        requireSupplier(request.getSupplierId());
        ImportOrder order = getOrder(id);
        ensureEditable(order);
        order.setSupplierId(request.getSupplierId());
        order.setImportDate(request.getImportDate());
        order.setTotalAmount(calculateTotal(request.getDetails()));
        order.setUpdatedBy(request.getStaffId());
        ImportOrder savedOrder = importOrderRepository.saveAndFlush(order);
        detailRepository.deleteAllByImportOrderId(id);
        saveDetails(savedOrder.getId(), request.getDetails());
        return toResponse(savedOrder);
    }

    @Transactional
    public void delete(UUID id) {
        ImportOrder order = getOrder(id);
        ensureEditable(order);
        order.softDelete();
        importOrderRepository.save(order);
    }

    @Transactional
    public ImportOrderResponse approve(UUID id) {
        ImportOrder order = getOrder(id);
        ensureEditable(order);
        List<ImportOrderDetail> details = detailRepository.findAllByImportOrderId(id);
        if (details.isEmpty()) {
            throw new BadRequestException("Phiếu nhập phải có ít nhất một sản phẩm");
        }

        try {
            for (ImportOrderDetail detail : details) {
                Product product = productRepository.findById(detail.getProductId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Product", "id", detail.getProductId()));
                int currentStock = product.getStockQuantity() == null ? 0 : product.getStockQuantity();
                product.setStockQuantity(currentStock + detail.getQuantity());
                productRepository.saveAndFlush(product);
            }
        } catch (ObjectOptimisticLockingFailureException | OptimisticLockException exception) {
            throw new BadRequestException("Tồn kho vừa được cập nhật bởi giao dịch khác, vui lòng thử lại");
        }

        order.setStatus(RECEIVED_STATUS);
        ImportOrder savedOrder = importOrderRepository.saveAndFlush(order);
        return toResponse(savedOrder);
    }

    private ImportOrder getOrder(UUID id) {
        return importOrderRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Import order", "id", id));
    }

    private void requireSupplier(UUID supplierId) {
        supplierRepository.findById(supplierId)
                .filter(supplier -> supplier.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", "id", supplierId));
    }

    private void ensureEditable(ImportOrder order) {
        if (!DRAFT_STATUS.equals(order.getStatus()) && !CONFIRMED_STATUS.equals(order.getStatus())) {
            throw new BadRequestException("Phiếu nhập không ở trạng thái có thể thay đổi");
        }
    }

    private BigDecimal calculateTotal(List<ImportOrderDetailRequest> details) {
        return details.stream()
                .map(detail -> detail.getImportPrice().multiply(BigDecimal.valueOf(detail.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void saveDetails(UUID orderId, List<ImportOrderDetailRequest> detailRequests) {
        List<ImportOrderDetail> details = detailRequests.stream()
                .map(detail -> ImportOrderDetail.builder()
                        .importOrderId(orderId)
                        .productId(detail.getProductId())
                        .quantity(detail.getQuantity())
                        .importPrice(detail.getImportPrice())
                        .build())
                .toList();
        detailRepository.saveAll(details);
    }

    private ImportOrderResponse toResponse(ImportOrder order) {
        return ImportOrderResponse.from(
                order,
                detailRepository.findAllByImportOrderId(order.getId()));
    }
}