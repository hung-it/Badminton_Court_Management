package com.bcm.service;

import com.bcm.dto.request.*;
import com.bcm.entity.*;
import com.bcm.repository.*;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class P2RegressionTest {
    @Test void newPasswordsRespectUtf8ByteBoundariesAndLegacyPasswordsRemainValid() {
        assertThatCode(()->com.bcm.security.CredentialPolicy.requireNewPassword("a".repeat(72))).doesNotThrowAnyException();
        assertThatCode(()->com.bcm.security.CredentialPolicy.requireNewPassword("ắ".repeat(24))).doesNotThrowAnyException();
        for(String password:List.of("a".repeat(73),"ắ".repeat(25),"short12")) {
            assertThatThrownBy(()->com.bcm.security.CredentialPolicy.requireNewPassword(password))
                    .isInstanceOf(com.bcm.exception.BadRequestException.class);
        }
        assertThatCode(()->com.bcm.security.CredentialPolicy.requireCurrentPassword("a".repeat(100))).doesNotThrowAnyException();
        assertThatThrownBy(()->com.bcm.security.CredentialPolicy.requireCurrentPassword("a".repeat(101)))
                .isInstanceOf(com.bcm.exception.BadRequestException.class);
    }

    @Test void counterUsesBookingContactAndFallsBackPerField() {
        var bookings=mock(BookingRepository.class);var details=mock(BookingDetailRepository.class);
        var profile=new Customer();profile.setFullName("Profile name");profile.setPhone("0912345678");
        var b=new Booking();b.setId(UUID.randomUUID());b.setCustomer(profile);b.setStatus(BookingStatus.PENDING);
        b.setCourtFee(BigDecimal.TEN);b.setDiscountAmount(BigDecimal.ZERO);
        b.setContactFullName("Booking contact");b.setContactPhone("0987654321");
        when(bookings.findByStatusInOrderByCreatedAtDesc(any())).thenReturn(List.of(b));
        when(details.findHistoryDetails(b.getId())).thenReturn(List.of());
        var service=new CheckoutService(bookings,details,null,null,null,null,null,Clock.systemUTC());
        var result=service.getActiveBookings().get(0);
        assertThat(result.customerName()).isEqualTo("Booking contact");
        assertThat(result.customerPhone()).isEqualTo("0987654321");
        b.setContactPhone(" ");
        assertThat(service.getActiveBookings().get(0).customerPhone()).isEqualTo("0912345678");
    }

    @Test void existingEightyBytePasswordCanBeChangedWithoutTruncatingTheNewPassword() {
        var users=mock(UserRepository.class);var encoder=new BCryptPasswordEncoder();
        UUID id=UUID.randomUUID();var user=new User();user.setId(id);
        String legacy="a".repeat(80);user.setPasswordHash(encoder.encode(legacy));
        when(users.findActiveForUpdate(id)).thenReturn(Optional.of(user));
        assertThatCode(()->new UserService(users,mock(com.bcm.repository.CustomerRepository.class),mock(com.bcm.security.WalkInGuestIdentity.class),encoder).changePassword(id.toString(),new ChangePasswordRequest(legacy,"NewPassword123")))
                .doesNotThrowAnyException();
        assertThat(encoder.matches("NewPassword123",user.getPasswordHash())).isTrue();
    }

    @Test void adminCannotAdvertiseAZeroPriceCourt() {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var r=new CourtRequest(1,"Court",CourtType.STANDARD_MAT,CourtStatus.AVAILABLE,BigDecimal.ZERO,null);
            assertThat(factory.getValidator().validate(r)).isNotEmpty();
        }
    }
    @Test void courtAndProductStatisticsKeepIdsInternalForEveryPeriodAndExcel() throws Exception {
        var allocation=mock(RevenueBreakdownService.class);
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        when(allocation.allocationValues(any(),any(),anyBoolean())).thenReturn(List.of(
                new RevenueBreakdownService.AllocationValue(a,"Cùng tên",BigDecimal.TEN),
                new RevenueBreakdownService.AllocationValue(b,"Cùng tên",BigDecimal.ONE)));
        var statistics=new StatisticsService(mock(InvoiceRepository.class),mock(ImportOrderRepository.class),mock(org.springframework.jdbc.core.JdbcTemplate.class),allocation);
        for(String type:List.of("COURT","PRODUCT"))for(String period:List.of("MONTH","QUARTER","YEAR","YEARS")) {
            var m=statistics.matrix(type,period,2026,2025,2026,null,null,"");
            assertThat(m.rows()).hasSize(2);assertThat(m.rows()).extracting(StatisticsService.MatrixRow::label).containsOnly("Cùng tên");
            assertThat(m.rows()).extracting(StatisticsService.MatrixRow::id).containsExactlyInAnyOrder(a.toString(),b.toString());
            try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(statistics.excel(m,"Tất cả")))) {
                for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())if(entry.getName().equals("xl/worksheets/sheet1.xml")) {
                    var xml=new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                    assertThat(xml).contains("Cùng tên").doesNotContain(a.toString(),b.toString());
                }
            }
        }
        verify(allocation,atLeastOnce()).allocationValues(any(),any(),eq(true));
        verify(allocation,atLeastOnce()).allocationValues(any(),any(),eq(false));
    }

    @Test void excelFilterMetadataUsesObjectNameInsteadOfUuid() {
        var statistics=mock(StatisticsService.class);String id=UUID.randomUUID().toString();
        var report=new StatisticsService.Matrix("STAFF",List.of("2026"),List.of(new StatisticsService.MatrixRow(id,"Thu ngân",List.of(BigDecimal.TEN),BigDecimal.TEN)),List.of(BigDecimal.TEN),BigDecimal.TEN,"");
        when(statistics.matrix("STAFF","YEAR",2026,2024,2026,null,null,id)).thenReturn(report);
        when(statistics.excel(any(),anyString())).thenReturn(new byte[0]);
        new com.bcm.controller.AnalyticsController(mock(AnalyticsService.class),statistics,mock(RevenueBreakdownService.class))
                .excel("STAFF","YEAR",2026,2024,2026,null,null,id);
        var filters=org.mockito.ArgumentCaptor.forClass(String.class);verify(statistics).excel(eq(report),filters.capture());
        assertThat(filters.getValue()).contains("Thu ngân").doesNotContain(id);
    }
    @Test void datePeriodUsesEveryInclusiveDayForAllSixTypesWithoutYearRestriction() {
        var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.queryForMap(anyString())).thenReturn(Map.of("first",java.sql.Date.valueOf("2026-09-20"),"last",java.sql.Date.valueOf("2026-11-13")));
        var invoices=mock(InvoiceRepository.class);var totals=mock(InvoiceRepository.RevenueTotals.class);
        when(invoices.sumRevenueByPeriod(anyString(),any(),any())).thenReturn(totals);
        when(totals.getTotalAmount()).thenReturn(BigDecimal.ZERO);
        var statistics=new StatisticsService(invoices,mock(ImportOrderRepository.class),jdbc,mock(RevenueBreakdownService.class));
        for(String type:List.of("TIME","IMPORT","STAFF","CUSTOMER","PRODUCT","COURT")) {
            for(int bounds=0;bounds<4;bounds++) {
                var m=statistics.matrix(type,"DATE",-1,-1,-1,(bounds&1)!=0?java.time.LocalDate.of(2026,9,20):null,(bounds&2)!=0?java.time.LocalDate.of(2026,11,13):null,"");
                assertThat(m.columns()).hasSize(55).startsWith("20/09/2026").endsWith("13/11/2026");
                assertThat(m.totals()).hasSize(55).containsOnly(BigDecimal.ZERO);
                assertThat(m.total()).isZero();
            }
        }
    }
}
