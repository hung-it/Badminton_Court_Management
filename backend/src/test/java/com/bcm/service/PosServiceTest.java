package com.bcm.service;

import com.bcm.dto.request.PosSaleItemRequest;
import com.bcm.dto.request.PosSaleRequest;
import com.bcm.entity.Product;
import com.bcm.entity.InvoiceDetail;
import com.bcm.entity.ProductType;
import com.bcm.exception.BadRequestException;
import com.bcm.repository.InvoiceDetailRepository;
import com.bcm.repository.InvoiceRepository;
import com.bcm.repository.ProductRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PosServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private InvoiceRepository invoiceRepository;

    @Mock
    private InvoiceDetailRepository invoiceDetailRepository;

    @Mock
    private com.bcm.repository.CustomerRepository customerRepository;

    @Mock
    private ProductPromotionService productPromotions;
    @Mock private ProductPriceService productPrices;

    @Mock private com.bcm.security.CurrentStaffService currentStaff;
    @Mock private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Mock private com.bcm.repository.PaymentTransactionRepository payments;

    @InjectMocks
    private PosService posService;

    @Test
    void optimisticLockConflictIsReturnedAsInsufficientStock() {
        UUID productId = UUID.randomUUID();
        Product product = new Product();
        product.setId(productId);
        product.setName("Nước suối");
        product.setType(ProductType.GOODS);
        product.setUnit("chai");
        product.setPrice(BigDecimal.TEN);
        product.setStockQuantity(1);
        product.setVersion(0);
        when(productRepository.findByIdForPriceApproval(productId)).thenReturn(Optional.of(product));
        when(productRepository.saveAndFlush(any(Product.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(Product.class, productId));

        PosSaleRequest request = new PosSaleRequest(
                UUID.randomUUID(),
                null,
                null,
                "CASH",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(new PosSaleItemRequest(productId, 1)));

        request.setIdempotencyKey(UUID.randomUUID());request.setPaymentConfirmed(true);
        when(currentStaff.requireUserId()).thenReturn(UUID.randomUUID());
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> posService.sell(request));

        assertEquals("Kho không đủ số lượng", exception.getMessage());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }
    @Test
    void salePersistsGoodsAndServiceTypeSnapshotsDespiteLaterProductChanges() {
        UUID productId=UUID.randomUUID(),actor=UUID.randomUUID(),registeredCustomer=UUID.randomUUID();
        Product product=new Product();product.setId(productId);product.setName("Water");product.setType(ProductType.GOODS);
        product.setPrice(BigDecimal.TEN);product.setStockQuantity(10);
        when(productRepository.findByIdForPriceApproval(productId)).thenReturn(Optional.of(product));
        when(currentStaff.requireUserId()).thenReturn(actor);
        when(productPromotions.best(any(),org.mockito.ArgumentMatchers.anyInt(),any(),any()))
                .thenReturn(new ProductPromotionService.Discount(null,null,BigDecimal.ZERO,BigDecimal.ZERO));
        when(invoiceRepository.saveAndFlush(any())).thenAnswer(call->{var invoice=(com.bcm.entity.Invoice)call.getArgument(0);invoice.setId(UUID.randomUUID());return invoice;});
        var customer=new com.bcm.entity.Customer();customer.setId(registeredCustomer);
        when(customerRepository.findById(registeredCustomer)).thenReturn(Optional.of(customer));
        for(String method:List.of("CASH","VNPAY")) {
            product.setType(method.equals("CASH")?ProductType.GOODS:ProductType.SERVICE);
            var r=new PosSaleRequest(registeredCustomer,null,null,method,BigDecimal.ZERO,BigDecimal.ZERO,List.of(new PosSaleItemRequest(productId,1)));
            r.setExpectedTotal(BigDecimal.TEN);
            r.setIdempotencyKey(UUID.randomUUID());r.setPaymentConfirmed(true);
            var saved=posService.sell(r);
            if(method.equals("VNPAY"))assertEquals(registeredCustomer,saved.getCustomerId());
            org.junit.jupiter.api.Assertions.assertNotNull(saved.getCustomerId());assertEquals("PAID",saved.getStatus());
        }
        org.mockito.Mockito.verify(payments,org.mockito.Mockito.times(2)).saveAndFlush(any());
        verify(customerRepository,never()).save(any());
        var detailWrites=org.mockito.Mockito.mockingDetails(invoiceDetailRepository).getInvocations().stream().filter(i->i.getMethod().getName().equals("saveAll")).toList();
        var first=(InvoiceDetail)((List<?>)detailWrites.get(0).getArgument(0)).get(0);
        var second=(InvoiceDetail)((List<?>)detailWrites.get(1).getArgument(0)).get(0);
        product.setType(ProductType.GOODS);
        assertEquals(ProductType.GOODS,first.getProductType());assertEquals(ProductType.SERVICE,second.getProductType());
    }
}
