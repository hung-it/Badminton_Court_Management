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
 public record State(boolean active) {}
 @GetMapping("/programs") public ApiResponse<?> programs(){return ApiResponse.success(service.programs(false));}
 @PostMapping("/programs") public ApiResponse<?> createProgram(@Valid @RequestBody CourtPromotionRequest.Program v){return ApiResponse.success(service.saveProgram(null,v));}
 @PutMapping("/programs/{id}") public ApiResponse<?> updateProgram(@PathVariable UUID id,@Valid @RequestBody CourtPromotionRequest.Program v){return ApiResponse.success(service.saveProgram(id,v));}
 @PatchMapping("/programs/{id}/status") public ApiResponse<?> programState(@PathVariable UUID id,@RequestBody State v){service.programState(id,v.active(),false);return ApiResponse.success("Đã cập nhật",null);}
 @DeleteMapping("/programs/{id}") public ApiResponse<?> deleteProgram(@PathVariable UUID id){service.programState(id,false,true);return ApiResponse.success("Đã ngừng chương trình",null);}
 @PatchMapping("/programs/{id}/rules/{rule}/status") public ApiResponse<?> ruleState(@PathVariable UUID id,@PathVariable UUID rule,@RequestBody State v){service.ruleState(id,rule,v.active(),false);return ApiResponse.success("Đã cập nhật",null);}
 @DeleteMapping("/programs/{id}/rules/{rule}") public ApiResponse<?> deleteRule(@PathVariable UUID id,@PathVariable UUID rule){service.ruleState(id,rule,false,true);return ApiResponse.success("Đã ngừng quy tắc",null);}
}
