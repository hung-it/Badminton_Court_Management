package com.bcm;

import com.bcm.controller.CourtController;
import com.bcm.dto.request.CourtBatchRequest;
import com.bcm.exception.GlobalExceptionHandler;
import com.bcm.service.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CourtBatchControllerTest {
    CourtBatchService batch;MockMvc mvc;
    @BeforeEach void setup(){
        batch=mock(CourtBatchService.class);
        mvc=MockMvcBuilders.standaloneSetup(new CourtController(mock(MasterDataService.class),batch)).setControllerAdvice(new GlobalExceptionHandler()).build();
    }
    String body(String quantity){return "{\"branchName\":\"New branch\",\"district\":\"Bình Thạnh\",\"address\":\"Address\",\"courtCount\":"+quantity+",\"type\":\"WOODEN_FLOOR\",\"status\":\"AVAILABLE\",\"basePrice\":150000}";}
    @Test void acceptsQuantityInsteadOfAnIndividualCourtNumber() throws Exception {
        when(batch.create(any())).thenReturn(List.of());
        mvc.perform(post("/admin/courts/batch").contentType(MediaType.APPLICATION_JSON).content(body("15"))).andExpect(status().isCreated());
        var request=ArgumentCaptor.forClass(CourtBatchRequest.class);verify(batch).create(request.capture());
        assertThat(request.getValue().courtCount()).isEqualTo(15);assertThat(request.getValue().branchId()).isNull();assertThat(request.getValue().branchName()).isEqualTo("New branch");
    }
    @Test void rejectsMissingZeroNegativeAndOversizedQuantityBeforeService() throws Exception {
        for(String count:List.of("null","0","-1","1001"))mvc.perform(post("/admin/courts/batch").contentType(MediaType.APPLICATION_JSON).content(body(count))).andExpect(status().isBadRequest());
        verifyNoInteractions(batch);
    }
    @Test void rejectsInvalidPriceAndControlCharactersBeforeCreatingAnything() throws Exception {
        mvc.perform(post("/admin/courts/batch").contentType(MediaType.APPLICATION_JSON).content(body("15").replace("150000","0"))).andExpect(status().isBadRequest());
        mvc.perform(post("/admin/courts/batch").contentType(MediaType.APPLICATION_JSON).content(body("15").replace("New branch","New\\nbranch"))).andExpect(status().isBadRequest());
        verifyNoInteractions(batch);
    }
}
