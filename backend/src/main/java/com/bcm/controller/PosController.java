package com.bcm.controller;

import com.bcm.dto.request.PosSaleRequest;
import com.bcm.dto.response.ApiResponse;
import com.bcm.entity.Invoice;
import com.bcm.service.PosService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/pos")
@RequiredArgsConstructor
public class PosController {

    private final PosService posService;

    @PostMapping("/sales")
    public ResponseEntity<ApiResponse<Invoice>> sell(@Valid @RequestBody PosSaleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Bán hàng thành công", posService.sell(request)));
    }
}