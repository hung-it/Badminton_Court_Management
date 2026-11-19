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
    @Mock private CourtPromotionService courtPromotions;

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

        request.setCustomerId(null);request.setCustomerPhone("0912345678");request.setIdempotencyKey(UUID.randomUUID());request.setPaymentConfirmed(true);
        when(currentStaff.requireUserId()).thenReturn(UUID.randomUUID());
        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> posService.sell(request));

        assertEquals("Kho không đủ số lượng", exception.getMessage());
        verify(invoiceRepository, never()).saveAndFlush(any());
    }
    @Test
    void walkInPhoneIsSavedOnInvoiceWithoutCreatingLoginAndRegisteredPhoneReusesProfile() {
        when(courtPromotions.bestAutomaticRule(any(),any())).thenReturn(null);
        UUID productId=UUID.randomUUID(),actor=UUID.randomUUID(),registeredCustomer=UUID.randomUUID();
        Product product=new Product();product.setId(productId);product.setName("Water");product.setType(ProductType.GOODS);
        product.setPrice(BigDecimal.TEN);product.setStockQuantity(10);
        when(productRepository.findByIdForPriceApproval(productId)).thenReturn(Optional.of(product));
        when(currentStaff.requireUserId()).thenReturn(actor);
        when(productPromotions.best(any(),org.mockito.ArgumentMatchers.anyInt(),any()))
                .thenReturn(new ProductPromotionService.Discount(null,null,BigDecimal.ZERO,BigDecimal.ZERO));
        when(invoiceRepository.saveAndFlush(any())).thenAnswer(call->{var invoice=(com.bcm.entity.Invoice)call.getArgument(0);invoice.setId(UUID.randomUUID());return invoice;});
        when(jdbc.queryForList(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.eq(UUID.class),org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call->"84912345678".equals(call.getArgument(2))?List.of(registeredCustomer):List.of());
        for(String method:List.of("CASH","VNPAY")) {
            product.setType(method.equals("CASH")?ProductType.GOODS:ProductType.SERVICE);
            var r=new PosSaleRequest(null,null,null,method,BigDecimal.ZERO,BigDecimal.ZERO,List.of(new PosSaleItemRequest(productId,1)));
            r.setCustomerPhone(method.equals("CASH")?"0912 345 678":"+84 912 345 678");r.setExpectedTotal(BigDecimal.TEN);
            r.setIdempotencyKey(UUID.randomUUID());r.setPaymentConfirmed(true);
            var saved=posService.sell(r);
            assertEquals(method.equals("CASH")?"0912345678":"84912345678",saved.getBuyerPhone());
            if(method.equals("VNPAY"))assertEquals(registeredCustomer,saved.getCustomerId());
            org.junit.jupiter.api.Assertions.assertNotNull(saved.getCustomerId());assertEquals("PAID",saved.getStatus());
        }
        var writes=org.mockito.Mockito.mockingDetails(jdbc).getInvocations().stream().filter(i->i.getMethod().getName().equals("update")).toList();
        assertEquals(1,writes.size());
        org.junit.jupiter.api.Assertions.assertTrue(((String)writes.get(0).getArgument(0)).contains("customers(id,user_id,full_name,phone,created_at,updated_at) VALUES (?,NULL"));
        org.mockito.Mockito.verify(payments,org.mockito.Mockito.times(2)).saveAndFlush(any());
        verify(customerRepository,never()).save(any());
        var detailWrites=org.mockito.Mockito.mockingDetails(invoiceDetailRepository).getInvocations().stream().filter(i->i.getMethod().getName().equals("saveAll")).toList();
        var first=(InvoiceDetail)((List<?>)detailWrites.get(0).getArgument(0)).get(0);
        var second=(InvoiceDetail)((List<?>)detailWrites.get(1).getArgument(0)).get(0);
        product.setType(ProductType.GOODS);
        assertEquals(ProductType.GOODS,first.getProductType());assertEquals(ProductType.SERVICE,second.getProductType());
    }

    @Test void productDiscountStacksOnlyWithTheBetterInvoiceOrVoucherRule() {
        UUID id=UUID.randomUUID();Product product=new Product();product.setId(id);product.setName("Water");product.setType(ProductType.GOODS);product.setPrice(new BigDecimal("100"));product.setStockQuantity(10);
        when(productRepository.findByIdAndDeletedAtIsNull(id)).thenReturn(Optional.of(product));
        when(productPromotions.best(any(),org.mockito.ArgumentMatchers.anyInt(),any())).thenReturn(new ProductPromotionService.Discount(UUID.randomUUID(),"Product",BigDecimal.TEN,new BigDecimal("100")));
        var automatic=new java.util.HashMap<String,Object>();automatic.put("id",UUID.randomUUID());automatic.put("promotion_id",UUID.randomUUID());automatic.put("promotion_code","AUTO");automatic.put("promotion_name","Invoice");
        when(courtPromotions.bestAutomaticRule(any(),any())).thenReturn(automatic);when(courtPromotions.amount(any(),any())).thenReturn(new BigDecimal("90"));
        var request=new PosSaleRequest(null,null,null,"CASH",BigDecimal.ZERO,BigDecimal.ZERO,List.of(new PosSaleItemRequest(id,10)));request.setCustomerPhone("0912345678");
        assertEquals(0,new BigDecimal("810").compareTo(posService.quote(request).totalAmount()));
        var voucher=new java.util.HashMap<String,Object>();voucher.put("id",UUID.randomUUID());voucher.put("promotion_id",automatic.get("promotion_id"));voucher.put("discount_type","AMOUNT");voucher.put("discount_value",new BigDecimal("80"));
        when(courtPromotions.voucher(org.mockito.ArgumentMatchers.eq("TEST"),any())).thenReturn(voucher);request.setPromotionCode("TEST");
        assertEquals(0,new BigDecimal("810").compareTo(posService.quote(request).totalAmount()));
        voucher.put("discount_value",new BigDecimal("100"));
        assertEquals(0,new BigDecimal("800").compareTo(posService.quote(request).totalAmount()));
        verify(courtPromotions,org.mockito.Mockito.times(3)).bestAutomaticRule(org.mockito.ArgumentMatchers.eq(new BigDecimal("900")),any());
    }
    @Test void selectedInvoiceRuleIsSnapshottedWithUnchangedDiscountAmount() {
        UUID productId=UUID.randomUUID(),ruleId=UUID.randomUUID(),promotionId=UUID.randomUUID();
        Product product=new Product();product.setId(productId);product.setName("Water");product.setType(ProductType.GOODS);product.setPrice(BigDecimal.TEN);product.setStockQuantity(10);
        when(productRepository.findByIdForPriceApproval(productId)).thenReturn(Optional.of(product));
        when(currentStaff.requireUserId()).thenReturn(UUID.randomUUID());
        when(productPromotions.best(any(),org.mockito.ArgumentMatchers.anyInt(),any())).thenReturn(new ProductPromotionService.Discount(null,null,BigDecimal.ZERO,BigDecimal.ZERO));
        var rule=new java.util.HashMap<String,Object>();rule.put("id",ruleId);rule.put("promotion_id",promotionId);rule.put("discount_value",BigDecimal.TEN);
        when(courtPromotions.bestAutomaticRule(any(),any())).thenReturn(rule);
        when(courtPromotions.amount(any(),any())).thenReturn(BigDecimal.ONE);
        when(invoiceRepository.saveAndFlush(any())).thenAnswer(c->c.getArgument(0));
        var request=new PosSaleRequest(null,null,null,"CASH",BigDecimal.ZERO,BigDecimal.ZERO,List.of(new PosSaleItemRequest(productId,1)));
        request.setCustomerPhone("0912345678");request.setExpectedTotal(new BigDecimal("9"));request.setIdempotencyKey(UUID.randomUUID());request.setPaymentConfirmed(true);
        var invoice=posService.sell(request);
        assertEquals(ruleId,invoice.getAppliedDiscountRuleId());assertEquals(promotionId,invoice.getPromotionId());
        assertEquals(0,new BigDecimal("9").compareTo(invoice.getTotalAmount()));assertEquals(0,BigDecimal.ONE.compareTo(invoice.getDiscountAmount()));
        rule.put("discount_value",new BigDecimal("50"));assertEquals(0,BigDecimal.ONE.compareTo(invoice.getDiscountAmount()));
    }
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
    static class PromotionSecurity {}
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="BCM_PROMOTION_READONLY_DB_URL",matches="jdbc:postgresql:.*")
    void postgresCatalogReadOnlyMatchesRealProgramRuleForeignKeys() {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(System.getenv("BCM_PROMOTION_READONLY_DB_URL"),System.getenv("BCM_PROMOTION_READONLY_DB_USER"),System.getenv("BCM_PROMOTION_READONLY_DB_PASSWORD"));
        var db=new org.springframework.jdbc.core.JdbcTemplate(source);
        var transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));transaction.setReadOnly(true);
        transaction.executeWithoutResult(t->{
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SHOW transaction_read_only",String.class)).isEqualTo("on");
            var service=new CourtPromotionService(db);var all=service.programs(false);
            org.assertj.core.api.Assertions.assertThat(all).hasSize(db.queryForObject("SELECT count(*) FROM promotions WHERE deleted_at IS NULL",Integer.class));
            long count=all.stream().mapToLong(p->((List<?>)p.get("rules")).size()).sum();
            org.assertj.core.api.Assertions.assertThat(count).isEqualTo(db.queryForObject("SELECT count(*) FROM discount_rules d JOIN promotions p ON p.id=d.promotion_id WHERE p.deleted_at IS NULL AND d.deleted_at IS NULL",Long.class));
            for(var program:all)for(var value:(List<?>)program.get("rules"))org.assertj.core.api.Assertions.assertThat(((java.util.Map<?,?>)value).get("promotion_id")).isEqualTo(program.get("id"));
            for(var program:service.programs(true))for(var value:(List<?>)program.get("rules"))org.assertj.core.api.Assertions.assertThat(((java.util.Map<?,?>)value).get("rule_type")).isEqualTo("PRODUCT");
            System.out.println("READ ONLY PostgreSQL catalog: "+all.size()+" programs, "+count+" linked rules; no writes");
        });
    }
    @Test void productRepositoryExcludesDisabledAndRemovedRulesFromFuturePricing() {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:product_rules_"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        var factory=new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();factory.setDataSource(source);factory.setPackagesToScan("com.bcm.entity");
        var vendor=new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter();vendor.setDatabase(org.springframework.orm.jpa.vendor.Database.H2);factory.setJpaVendorAdapter(vendor);
        factory.setJpaPropertyMap(java.util.Map.of("hibernate.hbm2ddl.auto","create-drop","hibernate.show_sql","false"));factory.afterPropertiesSet();
        var entityManager=factory.getObject().createEntityManager();
        try {
            var db=new org.springframework.jdbc.core.JdbcTemplate(source);UUID program=UUID.randomUUID();
            db.update("INSERT INTO promotions(id,code,name,valid_from,valid_to,is_active,created_at,updated_at) VALUES (?,'CHECK','Check',CURRENT_DATE,CURRENT_DATE,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",program);
            for(int i=0;i<3;i++)db.update("INSERT INTO discount_rules(id,promotion_id,rule_type,target_product_id,min_quantity,discount_type,discount_value,is_active,deleted_at,created_at) VALUES (?,?,'PRODUCT',?,1,'PERCENT',10,?,?,CURRENT_TIMESTAMP)",UUID.randomUUID(),program,UUID.randomUUID(),i!=1,i==2?java.time.LocalDateTime.now():null);
            var repo=new org.springframework.data.jpa.repository.support.JpaRepositoryFactory(entityManager).getRepository(com.bcm.repository.ProductPromotionRuleRepository.class);
            org.assertj.core.api.Assertions.assertThat(repo.applicable(java.time.LocalDate.now())).hasSize(1);
            org.assertj.core.api.Assertions.assertThat(repo.catalog()).hasSize(2);
            db.update("UPDATE promotions SET is_active=false WHERE id=?",program);entityManager.clear();
            org.assertj.core.api.Assertions.assertThat(repo.applicable(java.time.LocalDate.now())).isEmpty();
        } finally {entityManager.close();factory.destroy();}
    }
    @org.junit.jupiter.api.Nested
    class ProgramManagement {
        org.springframework.jdbc.core.JdbcTemplate db;
        org.springframework.transaction.support.TransactionTemplate transaction;
        CourtPromotionService campaigns;
        @org.junit.jupiter.api.BeforeEach void schema() {
            var source=new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:h2:mem:program_"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
            db=new org.springframework.jdbc.core.JdbcTemplate(source);
            transaction=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(source));
            campaigns=new CourtPromotionService(db);
            db.execute("CREATE TABLE products(id UUID PRIMARY KEY,name VARCHAR(255),deleted_at TIMESTAMP)");
            db.execute("CREATE TABLE promotions(id UUID PRIMARY KEY,code VARCHAR(50) UNIQUE,name VARCHAR(255),description VARCHAR(2000),valid_from DATE,valid_to DATE,is_active BOOLEAN,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,deleted_at TIMESTAMP)");
            db.execute("CREATE TABLE discount_rules(id UUID PRIMARY KEY,promotion_id UUID REFERENCES promotions(id),rule_type VARCHAR(20),target_product_id UUID REFERENCES products(id),min_quantity INT DEFAULT 1,discount_type VARCHAR(20),discount_value NUMERIC(10,2),min_invoice_amount NUMERIC(10,2),max_discount_amount NUMERIC(10,2),max_total_usage INT,max_usage_per_customer INT,voucher_code VARCHAR(50) UNIQUE,is_active BOOLEAN DEFAULT TRUE,deleted_at TIMESTAMP,created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,CHECK(rule_type<>'PRODUCT' OR target_product_id IS NOT NULL),CHECK(rule_type<>'VOUCHER' OR voucher_code IS NOT NULL))");
        }
        com.bcm.dto.request.CourtPromotionRequest.Rule rule(UUID id,String type,UUID product,String code,boolean active,boolean removed) {
            return new com.bcm.dto.request.CourtPromotionRequest.Rule(id,type,product,1,"PERCENT",BigDecimal.TEN,BigDecimal.ZERO,null,10,1,code,active,removed);
        }
        com.bcm.dto.request.CourtPromotionRequest.Program program(String name,java.util.List<com.bcm.dto.request.CourtPromotionRequest.Rule> rules) {
            return new com.bcm.dto.request.CourtPromotionRequest.Program(name,"Mô tả",java.time.LocalDate.now().minusDays(1),java.time.LocalDate.now().plusDays(1),true,rules);
        }
        UUID save(UUID id,com.bcm.dto.request.CourtPromotionRequest.Program value){return transaction.execute(t->campaigns.saveProgram(id,value));}
        UUID product(){UUID id=UUID.randomUUID();db.update("INSERT INTO products(id,name) VALUES (?,'Nước')",id);return id;}
        UUID ruleId(UUID id,String type){return db.queryForObject("SELECT id FROM discount_rules WHERE promotion_id=? AND rule_type=?",UUID.class,id,type);}
        @Test void oneProgramContainsAllActualRuleTypesAndSameNamesRemainSeparate() {
            UUID id=save(null,program("Cùng tên",List.of(rule(null,"PRODUCT",product(),null,true,false),rule(null,"INVOICE_TOTAL",null,null,true,false),rule(null,"VOUCHER",null,"MIXED",true,false))));
            save(null,program("Cùng tên",List.of(rule(null,"PRODUCT",product(),null,true,false))));
            var all=campaigns.programs(false);
            org.assertj.core.api.Assertions.assertThat(all).hasSize(2);
            var selected=all.stream().filter(v->v.get("id").equals(id)).findFirst().orElseThrow();
            org.assertj.core.api.Assertions.assertThat((List<?>)selected.get("rules")).hasSize(3);
            for(var visible:campaigns.programs(true))org.assertj.core.api.Assertions.assertThat((List<?>)visible.get("rules")).hasSize(1);
        }
        @Test void ruleRemovalPreservesProgramOtherRulesAndHistoricalIdentities() {
            UUID id=save(null,program("Hai quy tắc",List.of(rule(null,"INVOICE_TOTAL",null,null,true,false),rule(null,"VOUCHER",null,"KEEP",true,false))));
            UUID invoice=ruleId(id,"INVOICE_TOTAL"),voucher=ruleId(id,"VOUCHER");
            save(id,program("Hai quy tắc",List.of(rule(invoice,"INVOICE_TOTAL",null,null,true,true),rule(voucher,"VOUCHER",null,"KEEP",true,false))));
            org.assertj.core.api.Assertions.assertThat(campaigns.programs(false)).hasSize(1);
            org.assertj.core.api.Assertions.assertThat((List<?>)campaigns.programs(false).get(0).get("rules")).hasSize(1);
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT count(*) FROM discount_rules WHERE promotion_id=?",Integer.class,id)).isEqualTo(2);
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT discount_value FROM discount_rules WHERE id=?",BigDecimal.class,invoice)).isEqualByComparingTo("10");
        }
        @Test void foreignRuleIdRollsBackHeaderAndRules() {
            UUID first=save(null,program("A",List.of(rule(null,"INVOICE_TOTAL",null,null,true,false))));
            UUID second=save(null,program("B",List.of(rule(null,"INVOICE_TOTAL",null,null,true,false))));
            org.assertj.core.api.Assertions.assertThatThrownBy(()->save(first,program("Sai",List.of(rule(ruleId(second,"INVOICE_TOTAL"),"INVOICE_TOTAL",null,null,true,false))))).isInstanceOf(com.bcm.exception.BadRequestException.class);
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT name FROM promotions WHERE id=?",String.class,first)).isEqualTo("A");
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT count(*) FROM discount_rules",Integer.class)).isEqualTo(2);
        }
        @Test void newInvalidRuleRollsBackWholeProgram() {
            org.assertj.core.api.Assertions.assertThatThrownBy(()->save(null,program("Sai",List.of(rule(null,"PRODUCT",null,null,true,false))))).isInstanceOf(com.bcm.exception.BadRequestException.class);
            org.assertj.core.api.Assertions.assertThat(campaigns.programs(false)).isEmpty();
        }
        @Test void stateChangesOnlyFutureAutomaticPricesAndDeletionIsSoft() {
            UUID id=save(null,program("Tự động",List.of(rule(null,"INVOICE_TOTAL",null,null,true,false))));UUID rid=ruleId(id,"INVOICE_TOTAL");
            org.assertj.core.api.Assertions.assertThat(campaigns.automatic(new BigDecimal("1000"),java.time.LocalDate.now())).isEqualByComparingTo("100");
            transaction.executeWithoutResult(t->campaigns.ruleState(id,rid,false,false));
            org.assertj.core.api.Assertions.assertThat(campaigns.automatic(new BigDecimal("1000"),java.time.LocalDate.now())).isEqualByComparingTo("0");
            transaction.executeWithoutResult(t->campaigns.ruleState(id,rid,true,false));
            transaction.executeWithoutResult(t->campaigns.programState(id,false,false));
            org.assertj.core.api.Assertions.assertThat(campaigns.automatic(new BigDecimal("1000"),java.time.LocalDate.now())).isEqualByComparingTo("0");
            transaction.executeWithoutResult(t->campaigns.programState(id,true,false));
            org.assertj.core.api.Assertions.assertThat(campaigns.automatic(new BigDecimal("1000"),java.time.LocalDate.now())).isEqualByComparingTo("100");
            transaction.executeWithoutResult(t->campaigns.programState(id,false,true));
            org.assertj.core.api.Assertions.assertThat(campaigns.programs(false)).isEmpty();
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT count(*) FROM discount_rules",Integer.class)).isEqualTo(1);
        }
        @Test void multipleVoucherCodesUseRealRuleIdentityAndKeepExistingSelectionFormula() {
            UUID id=save(null,program("Voucher",List.of(rule(null,"VOUCHER",null,"ONE",true,false),rule(null,"VOUCHER",null,"TWO",true,false))));
            var selected=transaction.execute(t->campaigns.voucher("TWO",java.time.LocalDate.now()));
            org.assertj.core.api.Assertions.assertThat(selected.get("promotion_id")).isEqualTo(id);
            org.assertj.core.api.Assertions.assertThat(selected.get("voucher_code")).isEqualTo("TWO");
            org.assertj.core.api.Assertions.assertThat(campaigns.amount(selected,new BigDecimal("1000"))).isEqualByComparingTo("100");
            org.assertj.core.api.Assertions.assertThatThrownBy(()->save(null,program("Trùng",List.of(rule(null,"VOUCHER",null,"TWO",true,false))))).isInstanceOf(com.bcm.exception.DuplicateResourceException.class);
            org.assertj.core.api.Assertions.assertThat(campaigns.programs(false)).hasSize(1);
        }
        @Test void allRuleDeletionKeepsEmptyProgramAndProtectedVoucherCannotBeRewritten() {
            UUID id=save(null,program("Một quy tắc",List.of(rule(null,"INVOICE_TOTAL",null,null,true,false))));
            transaction.executeWithoutResult(t->campaigns.ruleState(id,ruleId(id,"INVOICE_TOTAL"),false,true));
            org.assertj.core.api.Assertions.assertThat(campaigns.programs(false)).hasSize(1);
            org.assertj.core.api.Assertions.assertThat((List<?>)campaigns.programs(false).get(0).get("rules")).isEmpty();
            org.assertj.core.api.Assertions.assertThatThrownBy(()->save(null,program("Sai",List.of(rule(null,"VOUCHER",null,"NEWMEMBER",true,false))))).isInstanceOf(com.bcm.exception.BadRequestException.class);
        }
        @Test void adminOnlyManagementAndCashierProductRulesRemainReadOnly() {
            try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.register(PromotionSecurity.class);
                context.registerBean(com.bcm.controller.CourtPromotionController.class,()->new com.bcm.controller.CourtPromotionController(campaigns));
                context.refresh();var controller=context.getBean(com.bcm.controller.CourtPromotionController.class);
                for(String role:List.of("STAFF","CUSTOMER")) {
                    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("test","test",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_"+role))));
                    org.assertj.core.api.Assertions.assertThatThrownBy(controller::programs).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                    org.assertj.core.api.Assertions.assertThatThrownBy(()->controller.createProgram(program("Denied",List.of(rule(null,"INVOICE_TOTAL",null,null,true,false))))).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
                }
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("test","test",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
                org.assertj.core.api.Assertions.assertThat(controller.programs()).isNotNull();
            } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        }
        @Test void addingVoucherCannotInvalidateUnclassifiedLegacyDraft() {
            UUID id=save(null,program("Cũ",List.of(rule(null,"INVOICE_TOTAL",null,null,true,false))));UUID existing=ruleId(id,"INVOICE_TOTAL");
            db.execute("CREATE TABLE invoices(id UUID,promotion_id UUID,status VARCHAR(20),applied_discount_rule_id UUID)");
            db.update("INSERT INTO invoices VALUES (?,?,'DRAFT',NULL)",UUID.randomUUID(),id);
            var updated=program("Mới",List.of(rule(existing,"INVOICE_TOTAL",null,null,true,false),rule(null,"VOUCHER",null,"LATER",true,false)));
            org.assertj.core.api.Assertions.assertThatThrownBy(()->save(id,updated)).isInstanceOf(com.bcm.exception.DuplicateResourceException.class);
            org.assertj.core.api.Assertions.assertThat(db.queryForObject("SELECT name FROM promotions WHERE id=?",String.class,id)).isEqualTo("Cũ");
            db.update("UPDATE invoices SET status='PAID'");save(id,updated);
            org.assertj.core.api.Assertions.assertThat((List<?>)campaigns.programs(false).get(0).get("rules")).hasSize(2);
        }
        @Test void checkoutUsageCheckUsesSelectedRuleRatherThanOtherVoucherInProgram() {
            UUID id=save(null,program("Kết hợp",List.of(rule(null,"INVOICE_TOTAL",null,null,true,false),rule(null,"VOUCHER",null,"A10",true,false),rule(null,"VOUCHER",null,"B10",true,false))));
            db.execute("CREATE TABLE customer_voucher_usage(invoice_id UUID,discount_rule_id UUID,customer_id UUID)");
            UUID invoice=UUID.randomUUID(),customer=UUID.randomUUID();
            UUID voucher=db.queryForObject("SELECT id FROM discount_rules WHERE voucher_code='A10'",UUID.class);
            db.update("INSERT INTO customer_voucher_usage VALUES (?,?,?)",invoice,voucher,customer);
            String sql="SELECT EXISTS (SELECT 1 FROM discount_rules d WHERE d.promotion_id=? AND (CAST(? AS UUID) IS NULL OR d.id=?) AND d.rule_type='VOUCHER' AND NOT EXISTS (SELECT 1 FROM customer_voucher_usage u WHERE u.invoice_id=? AND u.discount_rule_id=d.id AND u.customer_id=?))";
            org.assertj.core.api.Assertions.assertThat(db.queryForObject(sql,Boolean.class,id,voucher,voucher,invoice,customer)).isFalse();
            UUID automatic=ruleId(id,"INVOICE_TOTAL");
            org.assertj.core.api.Assertions.assertThat(db.queryForObject(sql,Boolean.class,id,automatic,automatic,invoice,customer)).isFalse();
            UUID unreserved=db.queryForObject("SELECT id FROM discount_rules WHERE voucher_code='B10'",UUID.class);
            org.assertj.core.api.Assertions.assertThat(db.queryForObject(sql,Boolean.class,id,unreserved,unreserved,invoice,customer)).isTrue();
        }
    }
}
