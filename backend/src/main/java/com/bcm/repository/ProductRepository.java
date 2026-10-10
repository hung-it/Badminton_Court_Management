package com.bcm.repository;

import com.bcm.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;
import java.util.List;

public interface ProductRepository extends JpaRepository<Product, UUID> {

	List<Product> findAllByDeletedAtIsNullOrderByNameAsc();
}