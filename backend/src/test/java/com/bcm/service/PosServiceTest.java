package com.bcm.service;

import com.bcm.dto.request.PosSaleItemRequest;
import com.bcm.dto.request.PosSaleRequest;
import com.bcm.entity.Product;
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
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));
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

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> posService.sell(request));

        assertEquals("Kho không đủ số lượng", exception.getMessage());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }
}
