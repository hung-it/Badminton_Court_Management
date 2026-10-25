package com.bcm.service;

import com.bcm.dto.request.CreateBookingRequest;
import com.bcm.dto.request.CreateBookingRequest.Detail;
import com.bcm.exception.BadRequestException;
import com.bcm.exception.DuplicateResourceException;
import com.bcm.security.CurrentCustomerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"booking.expiration-enabled=false", "booking.hold-duration=PT10M", "booking.payment.enabled-methods=VNPAY,BANK_TRANSFER"})
@AutoConfigureMockMvc
@Import(BookingWebTestConfiguration.class)
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class BranchBookingPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired BookingService service;
    @Autowired BookingExpirationService expiration;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @MockBean CurrentCustomerService currentCustomer;
    UUID user, customer, branch, otherBranch, normal, peak;
    List<UUID> courts;
    final LocalDate date=LocalDate.now().plusDays(3);

    @BeforeEach void fixtures() {
        user=UUID.randomUUID();customer=UUID.randomUUID();branch=UUID.randomUUID();otherBranch=UUID.randomUUID();
        normal=UUID.randomUUID();peak=UUID.randomUUID();
        courts=java.util.stream.IntStream.range(0,3).mapToObj(i->UUID.randomUUID()).sorted(Comparator.comparing(UUID::toString)).toList();
        when(currentCustomer.requireCustomerId()).thenReturn(customer);
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','Branch test','0900000000')",user,user+"@branch.test");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'Branch test','0900000000')",customer,user);
        for(UUID b:List.of(branch,otherBranch)) jdbc.update("INSERT INTO branches(id,code,name,district,address) VALUES (?,?, 'Branch test','Area','Area')",b,b.toString());
        for(int i=0;i<3;i++) jdbc.update("INSERT INTO courts(id,court_code,name,type,base_price,branch_id,court_number) VALUES (?,?,'Branch court','STANDARD_MAT',?,?,?)",courts.get(i),-93001-i,100000+i*10000,branch,i+1);
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,'12:00','13:00',1),(?,'17:00','18:00',1.5)",normal,peak);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM payment_transactions WHERE booking_id IN (SELECT id FROM bookings WHERE customer_id=?)",customer);
        jdbc.update("DELETE FROM bookings WHERE customer_id=?",customer);
        for(UUID court:courts) jdbc.update("DELETE FROM courts WHERE id=?",court);
        jdbc.update("DELETE FROM time_slots WHERE id IN (?,?)",normal,peak);
        jdbc.update("DELETE FROM branches WHERE id IN (?,?)",branch,otherBranch);
        jdbc.update("DELETE FROM customers WHERE id=?",customer);jdbc.update("DELETE FROM users WHERE id=?",user);
    }
    CreateBookingRequest request(Detail... items) {return new CreateBookingRequest(customer,List.of(items),branch);}
    Detail detail(int court,UUID slot) {return new Detail(date,courts.get(court),slot);}
    long count(String table) {return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE "+(table.equals("bookings")?"customer_id=?":"booking_id IN (SELECT id FROM bookings WHERE customer_id=?)"),Long.class,customer);}

    @Test void createsTwoCourtsWithDifferentTimesAndBackendPricesAndTenMinuteHold() {
        var result=service.createBooking(request(detail(0,normal),detail(0,peak),detail(1,peak)));
        assertThat(result.getDetails()).hasSize(3);
        assertThat(result.getCourtFee()).isEqualByComparingTo("415000");
        assertThat(jdbc.queryForObject("SELECT extract(epoch FROM expires_at-created_at) FROM bookings WHERE id=?",Double.class,result.getBookingId())).isBetween(599.0,601.0);
        assertThat(count("payment_transactions")).isZero();
    }
    @Test void threeCourtsUseOneBookingAndExpiryReleasesEveryDetailAtomically() {
        var result=service.createBooking(request(detail(0,normal),detail(1,normal),detail(2,peak)));
        assertThat(result.getCourtFee()).isEqualByComparingTo("390000");assertThat(count("bookings")).isEqualTo(1);
        jdbc.update("UPDATE bookings SET expires_at=TIMESTAMP '2000-01-01' WHERE id=?",result.getBookingId());
        assertThat(expiration.expireBooking(result.getBookingId())).isTrue();assertThat(count("booking_details")).isZero();
        assertThat(expiration.expireBooking(result.getBookingId())).isFalse();
        assertThat(jdbc.queryForObject("SELECT court_fee FROM bookings WHERE id=?",java.math.BigDecimal.class,result.getBookingId())).isEqualByComparingTo("390000");
        service.createBooking(request(detail(0,normal),detail(1,normal),detail(2,peak)));
    }
    @Test void branchMismatchUnknownInactiveAndDuplicateTuplesNeverCreatePartialBooking() {
        jdbc.update("UPDATE courts SET branch_id=? WHERE id=?",otherBranch,courts.get(2));
        assertThatThrownBy(()->service.createBooking(request(detail(0,normal),detail(2,peak)))).isInstanceOf(BadRequestException.class);
        var input=request(detail(0,normal));input.setBranchId(UUID.randomUUID());
        assertThatThrownBy(()->service.createBooking(input)).isInstanceOf(BadRequestException.class);
        jdbc.update("UPDATE branches SET active=false WHERE id=?",branch);
        assertThatThrownBy(()->service.createBooking(request(detail(0,normal)))).isInstanceOf(BadRequestException.class);
        jdbc.update("UPDATE branches SET active=true WHERE id=?",branch);
        assertThatThrownBy(()->service.createBooking(request(detail(0,normal),detail(0,normal)))).isInstanceOf(BadRequestException.class);
        assertThat(count("bookings")).isZero();assertThat(count("payment_transactions")).isZero();
    }
    @Test void implicitBranchAlsoRejectsMixedBranchesAndLegacySingleCourtStillWorks() {
        jdbc.update("UPDATE courts SET branch_id=? WHERE id=?",otherBranch,courts.get(2));
        var input=request(detail(0,normal),detail(2,peak));input.setBranchId(null);
        assertThatThrownBy(()->service.createBooking(input)).isInstanceOf(BadRequestException.class);
        jdbc.update("UPDATE courts SET branch_id=NULL,court_number=NULL WHERE id=?",courts.get(0));
        var legacy=new CreateBookingRequest(customer,List.of(detail(0,normal)));
        assertThat(service.createBooking(legacy).getDetails()).hasSize(1);
    }
    @Test void oneOccupiedTupleRollsBackAllOtherCourtsAndPastTimesAreRejected() {
        service.createBooking(request(detail(1,peak)));
        assertThatThrownBy(()->service.createBooking(request(detail(0,normal),detail(1,peak),detail(2,normal)))).isInstanceOf(DuplicateResourceException.class);
        assertThat(count("bookings")).isEqualTo(1);assertThat(count("booking_details")).isEqualTo(1);
        assertThatThrownBy(()->service.createBooking(request(new Detail(LocalDate.now().minusDays(1),courts.get(0),normal)))).isInstanceOf(BadRequestException.class);
        assertThat(count("bookings")).isEqualTo(1);
    }
    @Test void publicBranchCatalogAndCourtFilteringAreBackwardCompatible() throws Exception {
        mvc.perform(get("/public/branches")).andExpect(status().isOk()).andExpect(jsonPath("$.data[?(@.id == '"+branch+"')].courtCount").value(3));
        mvc.perform(get("/public/branches/"+branch+"/courts")).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get("/public/courts").param("branchId",branch.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(3));
        mvc.perform(get("/public/branches/"+UUID.randomUUID()+"/courts")).andExpect(status().isNotFound());
    }
    @Test void multiCourtTransferRetryCreatesOnePaymentForWholeServerTotal() throws Exception {
        var booking=service.createBooking(request(detail(0,normal),detail(1,peak),detail(2,normal)));
        String body="{\"paymentMethod\":\"BANK_TRANSFER\",\"bankTransfer\":{\"bankName\":\"Test\",\"accountHolder\":\"Test\",\"accountNumber\":\"123456\",\"transferContent\":\"Courts\"}}";
        for(int i=0;i<2;i++) mvc.perform(post("/bookings/"+booking.getBookingId()+"/payments").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
        assertThat(count("payment_transactions")).isEqualTo(1);
        assertThat(booking.getCourtFee()).isEqualByComparingTo("385000");
        assertThat(jdbc.queryForObject("SELECT amount FROM payment_transactions WHERE booking_id=?",java.math.BigDecimal.class,booking.getBookingId())).isEqualByComparingTo("355000");
        assertThat(jdbc.queryForObject("SELECT discount_amount FROM bookings WHERE id=?",java.math.BigDecimal.class,booking.getBookingId())).isEqualByComparingTo("30000");
        assertThat(count("booking_details")).isEqualTo(3);
    }
    @Test void concurrentReversedBranchRequestsHaveOneWinnerAndOneSlotConflict() throws Exception {
        var workers=Executors.newFixedThreadPool(2);var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
        try(var holder=dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try(var lock=holder.prepareStatement("SELECT id FROM courts WHERE id=? FOR UPDATE")) {lock.setObject(1,courts.get(0));lock.executeQuery().close();}
            try {
                var inputs=List.of(request(detail(0,normal),detail(1,peak)),request(detail(1,peak),detail(0,normal)));
                var results=inputs.stream().map(input->workers.submit(()->{
                    ready.countDown();assertThat(start.await(10,TimeUnit.SECONDS)).isTrue();
                    return mvc.perform(post("/bookings").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input))).andReturn().getResponse().getStatus();
                })).toList();
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();start.countDown();
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);boolean waiting=false;
                while(System.nanoTime()<deadline) {
                    var queries=jdbc.queryForList("SELECT query FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND state='active' AND lower(query) LIKE '%from%courts%' AND lower(query) LIKE '%for%update%'",String.class);
                    if(queries.size()==2){assertThat(queries).allSatisfy(q->assertThat(q.toLowerCase()).contains("order by"));waiting=true;break;}
                    Thread.sleep(20);
                }
                assertThat(waiting).as("two independent PostgreSQL lock waiters").isTrue();holder.commit();
                assertThat(List.of(results.get(0).get(20,TimeUnit.SECONDS),results.get(1).get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
                assertThat(count("bookings")).isEqualTo(1);assertThat(count("booking_details")).isEqualTo(2);assertThat(count("payment_transactions")).isZero();
            } finally {start.countDown();holder.rollback();workers.shutdownNow();workers.awaitTermination(20,TimeUnit.SECONDS);}
        }
    }
}
