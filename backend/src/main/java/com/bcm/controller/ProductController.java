package com.bcm.controller;

import com.bcm.dto.response.ApiResponse;
import com.bcm.entity.Product;
import com.bcm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductRepository productRepository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Product>>> findAll() {
        return ResponseEntity.ok(ApiResponse.success(productRepository.findAllByDeletedAtIsNullOrderByNameAsc()));
    }
}