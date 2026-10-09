package com.bcm.controller;
import com.bcm.entity.TimeSlot;
import com.bcm.dto.request.TimeSlotRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.service.MasterDataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;
@RestController @RequestMapping("/admin/time-slots") @RequiredArgsConstructor
public class TimeSlotController {
 private final MasterDataService service;
 @GetMapping public ApiResponse<List<TimeSlot>> list(){return ApiResponse.success(service.slots());}
 @GetMapping("/{id}") public ApiResponse<TimeSlot> get(@PathVariable UUID id){return ApiResponse.success(service.slot(id));}
 @PostMapping @ResponseStatus(HttpStatus.CREATED) public ApiResponse<TimeSlot> create(@Valid @RequestBody TimeSlotRequest r){return ApiResponse.success(service.saveSlot(null,r));}
 @PutMapping("/{id}") public ApiResponse<TimeSlot> update(@PathVariable UUID id,@Valid @RequestBody TimeSlotRequest r){return ApiResponse.success(service.saveSlot(id,r));}
 @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable UUID id){service.deleteSlot(id);return ApiResponse.success("Đã xóa");}
}
