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
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Transactional
    public ImportOrderResponse createManual(com.bcm.dto.request.ManualImportOrderRequest request) {
        String supplierName=request.supplierName().strip(),productName=request.productName().strip();
        if(supplierName.isBlank()||productName.isBlank())throw new BadRequestException("Vui lòng nhập nhà cung cấp và loại sản phẩm");
        // This real category row serializes name resolution/creation across independent imports.
        var categories=jdbc.queryForList("SELECT id FROM categories WHERE lower(category_name)=lower(?) AND deleted_at IS NULL ORDER BY id",UUID.class,"Hàng nhập kho");
        UUID categoryId=categories.isEmpty()?UUID.nameUUIDFromBytes("bcm:manual-import-category".getBytes(java.nio.charset.StandardCharsets.UTF_8)):categories.get(0);
        if(categories.isEmpty())jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,?) ON CONFLICT (id) DO NOTHING",categoryId,"Hàng nhập kho");
        if(jdbc.queryForList("SELECT id FROM categories WHERE id=? AND deleted_at IS NULL FOR UPDATE",UUID.class,categoryId).isEmpty())
            throw new BadRequestException("Danh mục Hàng nhập kho đã bị xóa; cần khôi phục danh mục trước khi nhập");
        var suppliers=jdbc.queryForList("SELECT id FROM suppliers WHERE lower(btrim(name))=lower(?) AND deleted_at IS NULL",UUID.class,supplierName);
        if(suppliers.size()>1)throw new com.bcm.exception.DuplicateResourceException("Có nhiều nhà cung cấp trùng tên; vui lòng nhập tên phân biệt");
        UUID supplierId=suppliers.isEmpty()?supplierRepository.saveAndFlush(com.bcm.entity.Supplier.builder().name(supplierName).build()).getId():suppliers.get(0);
        var products=jdbc.queryForList("SELECT id FROM products WHERE lower(btrim(name))=lower(?) AND type='GOODS' AND deleted_at IS NULL",UUID.class,productName);
        if(products.size()>1)throw new com.bcm.exception.DuplicateResourceException("Có nhiều sản phẩm trùng tên; vui lòng nhập tên phân biệt");
        UUID productId;
        if(products.isEmpty()) {
            Product product=new Product();product.setName(productName);product.setCategoryId(categoryId);
            product.setType(com.bcm.entity.ProductType.GOODS);product.setUnit("cái");product.setPrice(BigDecimal.ZERO);product.setStockQuantity(0);
            productId=productRepository.saveAndFlush(product).getId();
        }else productId=products.get(0);
        long cents=request.totalAmount().movePointRight(2).longValueExact();
        int quantity=request.quantity(),remainder=(int)(cents%quantity);long unit=cents/quantity;
        var lines=new java.util.ArrayList<ImportOrderDetailRequest>();
        if(quantity>remainder)lines.add(new ImportOrderDetailRequest(productId,quantity-remainder,BigDecimal.valueOf(unit,2)));
        if(remainder>0)lines.add(new ImportOrderDetailRequest(productId,remainder,BigDecimal.valueOf(unit+1,2)));
        return create(new ImportOrderRequest(supplierId,java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")),null,lines));
    }

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
        order.setStatus("CANCELLED");
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
