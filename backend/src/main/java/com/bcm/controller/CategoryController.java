package com.bcm.controller;
import com.bcm.entity.Category;
import com.bcm.dto.request.CategoryRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.service.MasterDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController @RequestMapping("/admin/categories") @RequiredArgsConstructor
public class CategoryController {
 private final MasterDataService service;
 @GetMapping public ApiResponse<List<Category>> list(){return ApiResponse.success(service.categories());}
 @GetMapping("/{id}") public ApiResponse<Category> get(@PathVariable UUID id){return ApiResponse.success(service.category(id));}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public ApiResponse<Category> create(@Valid @RequestBody CategoryRequest r){return ApiResponse.success(service.saveCategory(null,r));}
 @PutMapping("/{id}") public ApiResponse<Category> update(@PathVariable UUID id,@Valid @RequestBody CategoryRequest r){return ApiResponse.success(service.saveCategory(id,r));}
 @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable UUID id){service.deleteCategory(id);return ApiResponse.success("Đã xóa");}
}
