package com.bcm.service;

import com.bcm.dto.request.ImportOrderRequest;
import com.bcm.dto.request.ImportOrderDetailRequest;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="booking.expiration-enabled=false")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class ImportOrderTotalPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired ImportOrderService imports;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProductPriceService prices;
    @Autowired PosService pos;
    @Autowired MasterDataService master;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    UUID supplier,category,product;
    List<UUID> extraSuppliers=new ArrayList<>(),extraProducts=new ArrayList<>();
    @BeforeEach void setup() {
        supplier=UUID.randomUUID();category=UUID.randomUUID();product=UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers(id,name) VALUES (?,'Import total test')",supplier);
        jdbc.update("INSERT INTO categories(id,category_name) VALUES (?,'Import total test')",category);
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,stock_quantity) VALUES (?,?,'Import total test','GOODS',10000,0)",product,category);
    }
    @AfterEach void cleanup() {
        for(var id:extraSuppliers)jdbc.update("DELETE FROM import_orders WHERE supplier_id=?",id);
        for(var id:extraProducts)jdbc.update("DELETE FROM products WHERE id=?",id);
        for(var id:extraSuppliers)jdbc.update("DELETE FROM suppliers WHERE id=?",id);
        jdbc.update("DELETE FROM import_orders WHERE supplier_id=?",supplier);
        jdbc.update("DELETE FROM products WHERE id=?",product);
        jdbc.update("DELETE FROM categories WHERE id=?",category);
        jdbc.update("DELETE FROM suppliers WHERE id=?",supplier);
    }
    @Test void manualNamesReuseExistingReferences() {
        var result=imports.createManual(new com.bcm.dto.request.ManualImportOrderRequest(" import TOTAL test ","IMPORT total TEST",3,new BigDecimal("100000")));
        assertThat(result.getSupplierId()).isEqualTo(supplier);
        assertThat(result.getDetails()).allMatch(d->d.getProductId().equals(product));
        assertThat(result.getTotalAmount()).isEqualByComparingTo("100000");
    }
    void moveToImportCategory() {
        jdbc.update("INSERT INTO categories(category_name) SELECT 'Hàng nhập kho' WHERE NOT EXISTS (SELECT 1 FROM categories WHERE category_name='Hàng nhập kho' AND deleted_at IS NULL)");
        UUID id=jdbc.queryForObject("SELECT id FROM categories WHERE category_name='Hàng nhập kho' AND deleted_at IS NULL",UUID.class);
        jdbc.update("UPDATE products SET category_id=? WHERE id=?",id,product);
    }
    @Test void importedProductCannotBeQuotedOrSoldBeforePriceApproval() {
        moveToImportCategory();
        jdbc.update("UPDATE products SET price=0,stock_quantity=5 WHERE id=?",product);
        var request=new com.bcm.dto.request.PosSaleRequest(null,null,null,"CASH",BigDecimal.ZERO,BigDecimal.ZERO,List.of(new com.bcm.dto.request.PosSaleItemRequest(product,1)));
        assertThat(prices.sellable()).extracting(com.bcm.entity.Product::getId).doesNotContain(product);
        assertThatThrownBy(()->pos.quote(request)).hasMessageContaining("chưa được duyệt");
        assertThatThrownBy(()->pos.sell(request)).hasMessageContaining("chưa được duyệt");
        var before=master.product(product);
        assertThat(before.isPriceApprovalManaged()).isTrue();
        assertThatThrownBy(()->master.saveProduct(product,new com.bcm.dto.request.ProductRequest(before.getCategoryId(),before.getName(),before.getType(),before.getUnit(),BigDecimal.TEN,5,before.getVersion(),null)))
            .hasMessageContaining("Duyệt giá niêm yết");
        assertThatThrownBy(()->prices.approve(product,BigDecimal.ZERO,before.getVersion())).isInstanceOf(com.bcm.exception.BadRequestException.class);
        var approved=prices.approve(product,new BigDecimal("25000"),before.getVersion());
        assertThat(approved.getStockQuantity()).isEqualTo(5);
        assertThat(prices.sellable()).extracting(com.bcm.entity.Product::getId).contains(product);
        assertThat(pos.quote(request).totalAmount()).isEqualByComparingTo("25000");
        assertThatThrownBy(()->prices.approve(product,BigDecimal.TEN,before.getVersion())).isInstanceOf(com.bcm.exception.DuplicateResourceException.class);
    }
    @Test void concurrentPriceApprovalsDoNotOverwriteOneAnother() throws Exception {
        moveToImportCategory();
        jdbc.update("UPDATE products SET price=0 WHERE id=?",product);
        int version=master.product(product).getVersion();
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<Boolean> task=()->{ready.countDown();start.await();try{prices.approve(product,BigDecimal.TEN,version);return true;}catch(com.bcm.exception.DuplicateResourceException e){return false;}};
        try{
            var one=pool.submit(task);var two=pool.submit(task);assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(one.get(20,java.util.concurrent.TimeUnit.SECONDS),two.get(20,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void manualApiAcceptsTypedNamesAndValidatesRequiredFields() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/import-orders/manual").contextPath("/api")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"supplierName\":\"Import total test\",\"productName\":\"Import total test\",\"quantity\":3,\"totalAmount\":\"100000\"}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.supplierId").value(supplier.toString()))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.totalAmount").value(100000));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/import-orders/manual").contextPath("/api")
            .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user("admin").roles("ADMIN"))
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .content("{\"supplierName\":\" \",\"productName\":\"\",\"quantity\":0,\"totalAmount\":-1}"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM import_orders WHERE supplier_id=?",Integer.class,supplier)).isEqualTo(1);
    }
    @Test void manualNewNamesCreateReferencesAtomicallyAndReuseThem() {
        String name="Manual-"+product;
        var result=imports.createManual(new com.bcm.dto.request.ManualImportOrderRequest(name,name,3,new BigDecimal("100000")));
        extraSuppliers.add(result.getSupplierId());extraProducts.add(result.getDetails().get(0).getProductId());
        var second=imports.createManual(new com.bcm.dto.request.ManualImportOrderRequest(name.toLowerCase(Locale.ROOT),name.toLowerCase(Locale.ROOT),2,new BigDecimal("50000")));
        assertThat(second.getSupplierId()).isEqualTo(result.getSupplierId());
        assertThat(second.getDetails().get(0).getProductId()).isEqualTo(extraProducts.get(0));
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,extraProducts.get(0))).isZero();
        assertThat(jdbc.queryForObject("SELECT price FROM products WHERE id=?",BigDecimal.class,extraProducts.get(0))).isEqualByComparingTo("0");
        assertThat(result.getTotalAmount()).isEqualByComparingTo("100000");
    }
    @Test void ambiguousProductRollsBackNewSupplier() {
        UUID duplicate=UUID.randomUUID();extraProducts.add(duplicate);
        jdbc.update("INSERT INTO products(id,category_id,name,type,price,stock_quantity) VALUES (?,?,'Import total test','GOODS',10000,0)",duplicate,category);
        String name="Rollback-"+supplier;
        assertThatThrownBy(()->imports.createManual(new com.bcm.dto.request.ManualImportOrderRequest(name,"Import total test",1,BigDecimal.TEN)))
            .isInstanceOf(com.bcm.exception.DuplicateResourceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE name=?",Integer.class,name)).isZero();
    }
    @Test void concurrentManualImportsDoNotDuplicateSupplierOrProduct() throws Exception {
        String name="Concurrent-"+supplier;
        var ready=new java.util.concurrent.CountDownLatch(2);var start=new java.util.concurrent.CountDownLatch(1);
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Callable<com.bcm.dto.response.ImportOrderResponse> task=()->{ready.countDown();start.await();return imports.createManual(new com.bcm.dto.request.ManualImportOrderRequest(name,name,1,BigDecimal.TEN));};
        try {
            var one=pool.submit(task);var two=pool.submit(task);assertThat(ready.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            var first=one.get(20,java.util.concurrent.TimeUnit.SECONDS);var second=two.get(20,java.util.concurrent.TimeUnit.SECONDS);
            extraSuppliers.add(first.getSupplierId());extraProducts.add(first.getDetails().get(0).getProductId());
            assertThat(first.getId()).isNotEqualTo(second.getId());
            assertThat(first.getSupplierId()).isEqualTo(second.getSupplierId());
            assertThat(first.getDetails().get(0).getProductId()).isEqualTo(second.getDetails().get(0).getProductId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM suppliers WHERE name=?",Integer.class,name)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM products WHERE name=?",Integer.class,name)).isEqualTo(1);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @Test void splitPricesPersistExactEnteredTotalAndReceiveExactQuantityOnlyOnce() {
        var order=imports.create(new ImportOrderRequest(supplier,LocalDate.now(),null,List.of(
            new ImportOrderDetailRequest(product,2,new BigDecimal("33333.33")),
            new ImportOrderDetailRequest(product,1,new BigDecimal("33333.34")))));
        assertThat(order.getStatus()).isEqualTo("DRAFT");
        assertThat(order.getTotalAmount()).isEqualByComparingTo("100000");
        assertThat(imports.findById(order.getId()).getDetails()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT sum(quantity*import_price) FROM import_order_details WHERE import_order_id=?",BigDecimal.class,order.getId())).isEqualByComparingTo("100000");
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,product)).isZero();
        imports.approve(order.getId());
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,product)).isEqualTo(3);
        assertThatThrownBy(()->imports.approve(order.getId())).isInstanceOf(com.bcm.exception.BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT stock_quantity FROM products WHERE id=?",Integer.class,product)).isEqualTo(3);
    }
}
