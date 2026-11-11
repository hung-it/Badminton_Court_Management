package com.bcm.controller;
import com.bcm.dto.request.AdminAccountUpdateRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.service.AdminAccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;
@RestController @RequestMapping("/admin/accounts") @PreAuthorize("hasRole('ADMIN')") @RequiredArgsConstructor
@io.swagger.v3.oas.annotations.tags.Tag(name="Account administration", description="ADMIN only; roles are read-only and sourced from user_roles")
public class AdminAccountController {
 private final AdminAccountService service;
 @GetMapping public ApiResponse<?> list(@RequestParam(defaultValue="") String search,@RequestParam(defaultValue="") String role,@RequestParam(defaultValue="created") String sort,@RequestParam(defaultValue="0") int page){return ApiResponse.success(service.list(search,role,sort,page));}
 @GetMapping("/{id}") public ApiResponse<?> detail(@PathVariable UUID id){return ApiResponse.success(service.detail(id));}
 @PutMapping("/{id}") public ApiResponse<?> update(@PathVariable UUID id,@Valid @RequestBody AdminAccountUpdateRequest r){service.update(id,r);return ApiResponse.success("Account updated");}
 @DeleteMapping("/{id}") public ApiResponse<?> disable(@PathVariable UUID id){service.disable(id);return ApiResponse.success("Account locked");}
 @PostMapping("/{id}/unlock") public ApiResponse<?> unlock(@PathVariable UUID id){service.unlock(id);return ApiResponse.success("Account unlocked");}
}
