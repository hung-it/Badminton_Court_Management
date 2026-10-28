package com.bcm.repository;

import com.bcm.entity.ProductPromotionRule;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductPromotionRuleRepository extends JpaRepository<ProductPromotionRule, UUID> {
    @Query("select r from ProductPromotionRule r join fetch r.promotion p "
            + "where r.ruleType='PRODUCT' and r.discountType='PERCENT' and p.deletedAt is null "
            + "order by p.createdAt desc, r.id")
    List<ProductPromotionRule> catalog();

    @Query("select r from ProductPromotionRule r join fetch r.promotion p "
            + "where r.id=:id and r.ruleType='PRODUCT' and r.discountType='PERCENT' and p.deletedAt is null")
    Optional<ProductPromotionRule> findManaged(@Param("id") UUID id);

    @Query("select r from ProductPromotionRule r join fetch r.promotion p "
            + "where r.ruleType='PRODUCT' and r.discountType='PERCENT' and p.deletedAt is null "
            + "and p.active=true and p.validFrom<=:today and p.validTo>=:today "
            + "order by p.createdAt, r.id")
    List<ProductPromotionRule> applicable(@Param("today") LocalDate today);
}
