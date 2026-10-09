package com.bcm.controller;
import com.bcm.entity.Product;
import com.bcm.dto.request.ProductRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.service.MasterDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController @RequestMapping("/admin/products") @RequiredArgsConstructor
public class ProductController {
 private final MasterDataService service;
 @GetMapping public ApiResponse<List<Product>> list(){return ApiResponse.success(service.products());}
 @GetMapping("/{id}") public ApiResponse<Product> get(@PathVariable UUID id){return ApiResponse.success(service.product(id));}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Product> create(@Valid @RequestBody ProductRequest r){return ApiResponse.success(service.saveProduct(null,r));}
 @PutMapping("/{id}") public ApiResponse<Product> update(@PathVariable UUID id,@Valid @RequestBody ProductRequest r){return ApiResponse.success(service.saveProduct(id,r));}
 @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable UUID id){service.deleteProduct(id);return ApiResponse.success("Đã xóa");}
}
