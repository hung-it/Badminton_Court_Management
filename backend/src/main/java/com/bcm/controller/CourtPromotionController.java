package com.bcm.controller;
import com.bcm.dto.request.CourtPromotionRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.service.CourtPromotionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.*;
@RestController @RequiredArgsConstructor @RequestMapping("/admin/court-promotions")
@PreAuthorize("hasRole('ADMIN')") @Tag(name="Court promotion management")
public class CourtPromotionController {
 private final CourtPromotionService service;
 @GetMapping public ApiResponse<?> list(){return ApiResponse.success(service.list());}
 @PostMapping public ApiResponse<?> create(@Valid @RequestBody CourtPromotionRequest v){service.save(null,v);return ApiResponse.success("Đã lưu",null);}
 @PutMapping("/{id}") public ApiResponse<?> update(@PathVariable UUID id,@Valid @RequestBody CourtPromotionRequest v){service.save(id,v);return ApiResponse.success("Đã lưu",null);}
 @DeleteMapping("/{id}") public ApiResponse<?> remove(@PathVariable UUID id){service.remove(id);return ApiResponse.success("Đã xóa",null);}
}
