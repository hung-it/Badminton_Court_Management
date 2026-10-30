package com.bcm.repository;

import com.bcm.entity.ProductPromotion;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPromotionRepository extends JpaRepository<ProductPromotion, UUID> {
    boolean existsByCodeIgnoreCase(String code);
}
