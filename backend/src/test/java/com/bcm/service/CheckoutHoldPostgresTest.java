package com.bcm.service;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.entity.BookingStatus;
import com.bcm.security.UserPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"booking.expiration-enabled=true", "booking.expiration-check-interval=PT24H"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class CheckoutHoldPostgresTest {
    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 25, 12, 0);
    static final LocalDate DATE = LocalDate.of(2031, 2, 20);
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username", () -> System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password", () -> System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired BookingExpirationJob job;
    @Autowired BookingExpirationService expiration;
    @Autowired DataSource dataSource;
    @MockBean(name = "bookingExpirationClock") Clock clock;
    UUID userId, customerId;
    List<UUID> courts, slots;

    void time(long seconds) {
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        when(clock.instant()).thenReturn(NOW.plusSeconds(seconds).toInstant(ZoneOffset.UTC));
    }
    @BeforeEach void fixtures() {
        time(0);
        userId = UUID.randomUUID(); customerId = UUID.randomUUID();
        courts = List.of(UUID.randomUUID(), UUID.randomUUID());
        slots = List.of(UUID.randomUUID(), UUID.randomUUID());
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Hold test','0900000000')", userId, userId + "@hold.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Hold test','0900000000')", customerId, userId);
        for (int i=0; i<2; i++) {
            jdbc.update("INSERT INTO courts(id,court_code,name,base_price) VALUES (?,?,'Hold test',100000)", courts.get(i), -901-i);
            jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,?,?,1)", slots.get(i), java.sql.Time.valueOf((8+i)+":00:00"), java.sql.Time.valueOf((9+i)+":00:00"));
        }
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)", customerId);
        jdbc.update("DELETE FROM bookings WHERE customer_id=?", customerId);
        for (var id:courts) jdbc.update("DELETE FROM courts WHERE id=?", id);
        for (var id:slots) jdbc.update("DELETE FROM time_slots WHERE id=?", id);
        jdbc.update("DELETE FROM customers WHERE id=?", customerId);
        jdbc.update("DELETE FROM users WHERE id=?", userId);
    }
    org.springframework.test.web.servlet.request.RequestPostProcessor owner() {
        return user(new UserPrincipal(userId.toString(),userId+"@hold.test","test",List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")),true));
    }
    CreateBookingRequest request(UUID old) {
        var details = courts.stream().flatMap(c -> slots.stream().map(s -> new CreateBookingRequest.Detail(DATE,c,s))).toList();
        var input = new CreateBookingRequest(customerId, details); input.setReplacesBookingId(old); return input;
    }
    UUID create(UUID old) throws Exception {
        var result=mvc.perform(post("/api/bookings").contextPath("/api").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(old)))).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("data").path("bookingId").asText());
    }
    com.fasterxml.jackson.databind.JsonNode action(UUID id,String name) throws Exception {
        var result=mvc.perform(post("/api/bookings/"+id+"/checkout/"+name).contextPath("/api").with(owner()))
                .andExpect(status().isOk()).andReturn();
        return mapper.readTree(result.getResponse().getContentAsString()).path("data");
    }
    void state(UUID id,String status,int count) {
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id=?",String.class,id)).isEqualTo(status);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE booking_id=?",Integer.class,id)).isEqualTo(count);
    }
    LocalDateTime deadline(UUID id,String column) {
        return jdbc.queryForObject("SELECT "+column+" FROM bookings WHERE id=?",LocalDateTime.class,id);
    }
    @Test void leaveIsIdempotentAndDoesNotReleaseBeforeSixtySeconds() throws Exception {
        var id=create(null); var original=deadline(id,"expires_at");
        action(id,"leave"); time(30); action(id,"leave");
        assertThat(deadline(id,"checkout_release_at")).isEqualTo(NOW.plusSeconds(60));
        assertThat(deadline(id,"expires_at")).isEqualTo(original);
        time(59); job.expireDueBookings(); state(id,"PENDING",4);
        var grid=mvc.perform(get("/api/public/availability").contextPath("/api").param("date",DATE.toString())).andReturn();
        var data=mapper.readTree(grid.getResponse().getContentAsString()).path("data").path("courts");
        for(var c:data) if(courts.contains(UUID.fromString(c.path("courtId").asText())))
            for(var s:c.path("slots")) if(slots.contains(UUID.fromString(s.path("timeSlotId").asText()))) assertThat(s.path("available").asBoolean()).isFalse();
    }
    @Test void backgroundJobReleasesEveryCourtAndHourWithoutAnyBrowserRequest() throws Exception {
        var id=create(null); action(id,"leave");
        time(60); job.expireDueBookings(); state(id,"EXPIRED",0);
        job.expireDueBookings(); state(id,"EXPIRED",0);
        var next=create(null); state(next,"PENDING",4);
        assertThat(next).isNotEqualTo(id);
    }
    @Test void originalHoldDeadlineWinsWithoutExtension() throws Exception {
        var id=create(null); time(570); action(id,"leave");
        assertThat(deadline(id,"checkout_release_at")).isEqualTo(NOW.plusMinutes(10));
        time(599); job.expireDueBookings(); state(id,"PENDING",4);
        time(600); job.expireDueBookings(); state(id,"EXPIRED",0);
    }
    @Test void resumeCancelsReleaseButPreservesOriginalDeadline() throws Exception {
        var id=create(null); action(id,"leave"); time(59); action(id,"resume"); action(id,"resume");
        assertThat(deadline(id,"checkout_release_at")).isNull();
        assertThat(deadline(id,"expires_at")).isEqualTo(NOW.plusMinutes(10));
        time(61); job.expireDueBookings(); state(id,"PENDING",4);
        time(600); job.expireDueBookings(); state(id,"EXPIRED",0);
    }
    @Test void resumeAtDeadlineExpiresEvenBeforeJobRunsAndCannotPay() throws Exception {
        var id=create(null); action(id,"leave"); time(60);
        assertThat(action(id,"resume").path("status").asText()).isEqualTo("EXPIRED"); state(id,"EXPIRED",0);
        action(id,"resume"); state(id,"EXPIRED",0);
        mvc.perform(post("/api/bookings/"+id+"/payments").contextPath("/api").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"paymentMethod\":\"BANK_TRANSFER\",\"bankTransfer\":{\"bankName\":\"Test bank\",\"accountHolder\":\"Test customer\",\"accountNumber\":\"1234567890\",\"transferContent\":\"Booking payment\"}}"))
                .andExpect(status().isConflict());
    }
    @Test void leavingAfterMoreThanAMinuteStartsANewSixtySecondReleaseWindow() throws Exception {
        var old=create(null); time(125); action(old,"leave");
        assertThat(deadline(old,"checkout_release_at")).isEqualTo(NOW.plusSeconds(185));
        time(184); job.expireDueBookings(); state(old,"PENDING",4);
        time(185); job.expireDueBookings(); state(old,"EXPIRED",0);
    }
    @Test void newBookingOnDifferentTuplesNeverReleasesOrRenewsOldBooking() throws Exception {
        var old=create(null); time(125); action(old,"leave"); time(155);
        var input=request(old); input.getDetails().forEach(d->d.setBookingDate(DATE.plusDays(1)));
        var result=mvc.perform(post("/api/bookings").contextPath("/api").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isCreated()).andReturn();
        var next=UUID.fromString(mapper.readTree(result.getResponse().getContentAsString()).path("data").path("bookingId").asText());
        assertThat(next).isNotEqualTo(old);state(old,"PENDING",4);state(next,"PENDING",4);
        assertThat(deadline(old,"checkout_release_at")).isEqualTo(NOW.plusSeconds(185));
        assertThat(deadline(old,"expires_at")).isEqualTo(NOW.plusSeconds(600));
        assertThat(deadline(next,"expires_at")).isEqualTo(NOW.plusSeconds(755));
        time(185); job.expireDueBookings();state(old,"EXPIRED",0);state(next,"PENDING",4);
    }
    @Test void ownerCannotRebookOrAdvertiseHeldSlotsAsFreeBeforeRelease() throws Exception {
        var old=create(null); action(old,"leave");time(59);
        mvc.perform(post("/api/bookings").contextPath("/api").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(old)))).andExpect(status().isConflict());
        var result=mvc.perform(get("/api/bookings/"+old+"/checkout/hold").contextPath("/api").with(owner()))
                .andExpect(status().isOk()).andReturn();
        assertThat(mapper.readTree(result.getResponse().getContentAsString()).path("data").path("replaceable").asBoolean()).isFalse();
        state(old,"PENDING",4);
    }
    @Test void failedReplacementRollsBackOldStatusAndEverySlot() throws Exception {
        var old=create(null); action(old,"leave"); var input=request(old);
        input.getDetails().get(0).setTimeSlotId(UUID.randomUUID());
        mvc.perform(post("/api/bookings").contextPath("/api").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input))).andExpect(status().isNotFound());
        state(old,"PENDING",4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE customer_id=?",Integer.class,customerId)).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"PENDING","SUCCESS"})
    void protectedPaymentPreventsEarlyReleaseAndReplacement(String status) throws Exception {
        var old=create(null); action(old,"leave");
        var payment=UUID.randomUUID();
        jdbc.update("INSERT INTO payment_transactions(id,booking_id,payment_method,status,amount) VALUES (?,?,'VNPAY',?,400000)",payment,old,status);
        var before=jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id=?",payment);
        time(60); job.expireDueBookings(); state(old,"PENDING",4);
        mvc.perform(post("/api/bookings").contextPath("/api").with(owner()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(request(old)))).andExpect(status().isConflict());
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE id=?",payment)).isEqualTo(before);
    }
    @Test void paidBookingNeverReleases() throws Exception {
        var id=create(null); action(id,"leave"); jdbc.update("UPDATE bookings SET status='PAID' WHERE id=?",id);
        time(700); job.expireDueBookings(); action(id,"leave"); action(id,"resume"); state(id,"PAID",4);
    }
    @Test void alreadyExpiredBookingCanBeReplacedWithoutChangingLatePaymentHistory() throws Exception {
        var old=create(null); action(old,"leave");
        jdbc.update("INSERT INTO payment_transactions(booking_id,payment_method,status,amount) VALUES (?,'VNPAY','PENDING',400000)",old);
        time(600); job.expireDueBookings(); state(old,"EXPIRED",0);
        var before=jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id=?",old);
        var next=create(old); state(next,"PENDING",4); state(old,"EXPIRED",0);
        assertThat(jdbc.queryForMap("SELECT * FROM payment_transactions WHERE booking_id=?",old)).isEqualTo(before);
    }
    @Test void ownershipAndAuthenticationProtectAllEndpointsAndReplacement() throws Exception {
        var id=create(null); action(id,"leave");
        for(var name:List.of("leave","resume")) {
            mvc.perform(post("/api/bookings/"+id+"/checkout/"+name).contextPath("/api")).andExpect(status().isUnauthorized());
        }
        // Foreign identity has an active profile but cannot schedule/resume/inspect/replace this hold.
        var foreignUser=UUID.randomUUID(); var foreignCustomer=UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Foreign','0900000000')",foreignUser,foreignUser+"@hold.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Foreign','0900000000')",foreignCustomer,foreignUser);
        var ownerUser=userId; var ownerCustomer=customerId;
        try {
            userId=foreignUser; customerId=foreignCustomer;
            for(var name:List.of("leave","resume")) mvc.perform(post("/api/bookings/"+id+"/checkout/"+name).contextPath("/api").with(owner())).andExpect(status().isForbidden());
            mvc.perform(get("/api/bookings/"+id+"/checkout/hold").contextPath("/api").with(owner())).andExpect(status().isForbidden());
            mvc.perform(post("/api/bookings").contextPath("/api").with(owner()).contentType(MediaType.APPLICATION_JSON)
                    .content(mapper.writeValueAsString(request(id)))).andExpect(status().isForbidden());
            state(id,"PENDING",4);
        } finally {
            userId=ownerUser; customerId=ownerCustomer;
            jdbc.update("DELETE FROM customers WHERE id=?",foreignCustomer); jdbc.update("DELETE FROM users WHERE id=?",foreignUser);
        }
    }
    @Test void concurrentRequestsAfterReleaseHaveExactlyOneWinner() throws Exception {
        var old=create(null); action(old,"leave");
        time(60); job.expireDueBookings();state(old,"EXPIRED",0);
        var workers=Executors.newFixedThreadPool(2); var start=new CountDownLatch(1);
        try(var holder=dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try(var lock=holder.prepareStatement("SELECT id FROM bookings WHERE id=? FOR UPDATE")) {lock.setObject(1,old);lock.executeQuery().close();}
            Callable<Integer> call=()->{start.await();return mvc.perform(post("/api/bookings").contextPath("/api").with(owner())
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(request(old)))).andReturn().getResponse().getStatus();};
            var a=workers.submit(call); var b=workers.submit(call); start.countDown();
            long limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(15); boolean waiting=false;
            while(System.nanoTime()<limit) {
                int count=jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND lower(query) LIKE '%from%bookings%for%update%'",Integer.class);
                if(count==2){waiting=true;break;} Thread.sleep(20);
            }
            assertThat(waiting).as("two independent replacement transactions waiting on the Booking lock").isTrue();
            holder.commit(); assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
            state(old,"EXPIRED",0);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_details WHERE court_id IN (?,?)",Integer.class,courts.get(0),courts.get(1))).isEqualTo(4);
        } finally {workers.shutdownNow();assertThat(workers.awaitTermination(20,TimeUnit.SECONDS)).isTrue();}
    }
}
