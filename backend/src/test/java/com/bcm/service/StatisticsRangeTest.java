package com.bcm.service;

import com.bcm.exception.BadRequestException;
import com.bcm.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StatisticsRangeTest {
 @Test void accepts366DaysAndRejectsOutOfRangeDates() {
  var jdbc=mock(JdbcTemplate.class);
  when(jdbc.queryForMap(anyString())).thenReturn(java.util.Map.of());
  var service=new StatisticsService(mock(InvoiceRepository.class),mock(ImportOrderRepository.class),jdbc,mock(RevenueBreakdownService.class));
  assertThat(service.matrix("DATE","DATE",2024,2024,2024,LocalDate.of(2024,1,1),LocalDate.of(2024,12,31),"").columns()).hasSize(366);
  assertThatThrownBy(()->service.matrix("DATE","DATE",2024,2024,2024,LocalDate.of(9999,1,1),LocalDate.of(9999,1,1),""))
   .isInstanceOf(BadRequestException.class);
 }
 @Test void rejectsOversizedDailyReportsBeforeQueryingDatabase() {
  var jdbc=mock(JdbcTemplate.class);
  var service=new StatisticsService(mock(InvoiceRepository.class),mock(ImportOrderRepository.class),jdbc,mock(RevenueBreakdownService.class));
  assertThatThrownBy(()->service.matrix("DATE","DATE",2026,2026,2026,LocalDate.of(2024,1,1),LocalDate.of(2025,1,1),""))
   .isInstanceOf(BadRequestException.class).hasMessageContaining("366");
  verifyNoInteractions(jdbc);
 }
}
