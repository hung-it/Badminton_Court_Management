package com.bcm.service;

import com.bcm.dto.request.ProductPromotionRequest;
import com.bcm.dto.response.ProductPromotionResponse;
import com.bcm.entity.*;
import com.bcm.exception.*;
import com.bcm.repository.*;
import java.math.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductPromotionService {
    private final ProductPromotionRepository promotions;
    private final ProductPromotionRuleRepository rules;
    private final ProductRepository products;

    public List<ProductPromotionResponse> list() {
        return rules.catalog().stream().map(this::response).toList();
    }

    @Transactional
    public ProductPromotionResponse save(UUID id, ProductPromotionRequest request) {
        if (request.validTo().isBefore(request.validFrom()))
            throw new BadRequestException("Ngày kết thúc phải từ ngày bắt đầu trở đi");
        products.findByIdAndDeletedAtIsNull(request.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy sản phẩm"));
        ProductPromotionRule rule = id == null ? new ProductPromotionRule() : managed(id);
        ProductPromotion promotion = id == null ? new ProductPromotion() : rule.getPromotion();
        String code=request.code()==null?"":request.code().strip().toUpperCase(Locale.ROOT);
        if(id==null){
            if(code.isEmpty())code="PRODUCT-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            if(promotions.existsByCodeIgnoreCase(code))throw new DuplicateResourceException("Mã khuyến mãi đã tồn tại");
            promotion.setCode(code);
        }else if(!code.isEmpty()&&!code.equalsIgnoreCase(promotion.getCode()))throw new BadRequestException("Không đổi mã chương trình đã tạo");
        promotion.setName(request.name().strip());
        promotion.setValidFrom(request.validFrom());
        promotion.setValidTo(request.validTo());
        promotion.setActive(request.active());
        promotions.saveAndFlush(promotion);
        rule.setPromotion(promotion);
        rule.setProductId(request.productId());
        rule.setMinQuantity(request.minQuantity());
        rule.setDiscountPercent(request.discountPercent());
        rules.saveAndFlush(rule);
        return response(rule);
    }

    @Transactional
    public void remove(UUID id) {
        ProductPromotion promotion = managed(id).getPromotion();
        promotion.setActive(false);
        promotion.softDelete();
        promotions.saveAndFlush(promotion);
    }

    private ProductPromotionRule managed(UUID id) {
        return rules.findManaged(id).orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy khuyến mãi sản phẩm"));
    }

    private ProductPromotionResponse response(ProductPromotionRule rule) {
        ProductPromotion p = rule.getPromotion();
        String name = products.findById(rule.getProductId()).map(Product::getName).orElse("Sản phẩm đã xóa");
        return new ProductPromotionResponse(rule.getId(), p.getName(), rule.getProductId(), name,
                rule.getMinQuantity(), rule.getDiscountPercent(), p.getValidFrom(), p.getValidTo(), p.isActive(),p.getCode());
    }

    public List<ProductPromotionRule> applicable() {
        return rules.applicable(LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh")));
    }

    public ProductPromotionRule validatePosCode(String code, Map<UUID,Integer> quantities) {
        if(code==null||code.isBlank())return null;
        var rule=rules.findByCodeForPos(code.strip().toUpperCase(Locale.ROOT))
            .orElseThrow(()->new BadRequestException("Mã khuyến mãi sản phẩm không tồn tại"));
        var promotion=rule.getPromotion();
        var today=LocalDate.now(ZoneId.of("Asia/Ho_Chi_Minh"));
        if(promotion.getDeletedAt()!=null||!promotion.isActive()||today.isBefore(promotion.getValidFrom())
                ||promotion.getValidTo()==null||today.isAfter(promotion.getValidTo()))
            throw new BadRequestException("Mã khuyến mãi đã tắt hoặc không còn hiệu lực");
        long totalQuantity=quantities.values().stream().mapToLong(Integer::longValue).sum();
        if(totalQuantity<rule.getMinQuantity())throw new BadRequestException("Mã yêu cầu tổng số lượng trong giỏ ít nhất "+rule.getMinQuantity()+"; hiện có "+totalQuantity);
        return rule;
    }

    public Discount best(Product product,int quantity,List<ProductPromotionRule> candidates,ProductPromotionRule enteredCode) {
        var automatic=best(product,quantity,candidates);
        if(enteredCode==null)return automatic;
        BigDecimal gross=product.getPrice().multiply(BigDecimal.valueOf(quantity));
        BigDecimal amount=gross.multiply(enteredCode.getDiscountPercent()).divide(new BigDecimal("100"),2,RoundingMode.HALF_UP);
        return amount.compareTo(automatic.amount())>0?new Discount(enteredCode.getPromotion().getId(),enteredCode.getPromotion().getName(),enteredCode.getDiscountPercent(),amount):automatic;
    }

    public Discount best(Product product, int quantity, List<ProductPromotionRule> candidates) {
        BigDecimal gross = product.getPrice().multiply(BigDecimal.valueOf(quantity)).setScale(2, RoundingMode.HALF_UP);
        Discount best = new Discount(null, null, BigDecimal.ZERO, BigDecimal.ZERO.setScale(2));
        for (ProductPromotionRule rule : candidates) {
            if (!product.getId().equals(rule.getProductId()) || quantity < rule.getMinQuantity()) continue;
            BigDecimal amount = gross.multiply(rule.getDiscountPercent()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            if (amount.compareTo(best.amount()) > 0)
                best = new Discount(rule.getPromotion().getId(), rule.getPromotion().getName(), rule.getDiscountPercent(), amount);
        }
        return best;
    }

    public record Discount(UUID promotionId, String name, BigDecimal percent, BigDecimal amount) {}
}
