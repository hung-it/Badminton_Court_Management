package com.bcm.controller;
import com.bcm.dto.request.AdminCustomerRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.service.AdminCustomerService;
import lombok.RequiredArgsConstructor;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @RequestMapping("/admin/customers") @RequiredArgsConstructor @PreAuthorize("hasRole('ADMIN')")
public class AdminCustomerController {
 private final AdminCustomerService service;
 @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="created") String sort){return ApiResponse.success(service.list(search,page,sort));}
 @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable UUID id){return ApiResponse.success(service.detail(id));}
 @PostMapping public ApiResponse<?> create(@Valid @RequestBody AdminCustomerRequest r){return ApiResponse.success(service.save(null,r));}
 @PutMapping("/{id}") public ApiResponse<?> update(@PathVariable UUID id,@Valid @RequestBody AdminCustomerRequest r){return ApiResponse.success(service.save(id,r));}
 @DeleteMapping("/{id}") public ApiResponse<?> disable(@PathVariable UUID id){service.disable(id);return ApiResponse.success("Customer disabled");}
}
