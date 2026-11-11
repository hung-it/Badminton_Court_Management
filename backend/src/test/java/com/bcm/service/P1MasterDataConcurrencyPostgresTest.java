package com.bcm.service;

import com.bcm.dto.request.*;
import com.bcm.exception.*;
import com.bcm.security.CurrentCustomerService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;

@SpringBootTest(properties={"booking.expiration-enabled=false","booking.auto-checkout.enabled=false"})
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class P1MasterDataConcurrencyPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired BookingService booking;
    @Autowired BookingModificationService modification;
    @Autowired MasterDataService master;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @MockBean CurrentCustomerService customer;
    UUID user,owner,court,slot;
    final LocalDate date=LocalDate.of(2090,1,1);
    @BeforeEach void setup() {
        user=UUID.randomUUID();owner=UUID.randomUUID();court=UUID.randomUUID();slot=UUID.randomUUID();
        when(customer.requireCustomerId()).thenReturn(owner);
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,'test','P1 lock','0912345678')",user,user+"@p1.invalid");
        jdbc.update("INSERT INTO customers(id,user_id,full_name,phone) VALUES (?,?,'P1 lock','0912345678')",owner,user);
        jdbc.update("INSERT INTO courts(id,court_code,name,base_price) VALUES (?,100090001,'P1 lock',100000)",court);
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,'00:00','01:00',1)",slot);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM bookings WHERE customer_id=?",owner);
        jdbc.update("DELETE FROM courts WHERE id=?",court);
        jdbc.update("DELETE FROM time_slots WHERE id=?",slot);
        jdbc.update("DELETE FROM users WHERE id=?",user);
    }
    CreateBookingRequest request() {return new CreateBookingRequest(owner,List.of(new CreateBookingRequest.Detail(date,court,slot)));}
    TimeSlotRequest changedSlot() {return new TimeSlotRequest(LocalTime.of(0,5),LocalTime.of(0,55),new BigDecimal("2.00"));}

    @RepeatedTest(10) void bookingWinsThenCourtDeletionIsRejected() throws Exception {
        assertThat(heldRace(()->booking.createBooking(request()),()->master.deleteCourt(court))).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM courts WHERE id=?",Boolean.class,court)).isTrue();
    }
    @RepeatedTest(10) void courtDeletionWinsThenBookingIsRejected() throws Exception {
        assertThat(heldRace(()->master.deleteCourt(court),()->booking.createBooking(request()))).isInstanceOf(BadRequestException.class);
        assertNoBooking();
    }
    @RepeatedTest(10) void bookingWinsThenSlotDeletionIsRejected() throws Exception {
        assertThat(heldRace(()->booking.createBooking(request()),()->master.deleteSlot(slot))).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM time_slots WHERE id=?",Boolean.class,slot)).isTrue();
    }
    @RepeatedTest(10) void bookingWinsThenSlotTimeChangeIsRejected() throws Exception {
        assertThat(heldRace(()->booking.createBooking(request()),()->master.saveSlot(slot,changedSlot()))).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT start_time FROM time_slots WHERE id=?",LocalTime.class,slot)).isEqualTo(LocalTime.MIDNIGHT);
    }
    @RepeatedTest(10) void slotDeletionWinsThenBookingIsRejected() throws Exception {
        assertThat(heldRace(()->master.deleteSlot(slot),()->booking.createBooking(request()))).isInstanceOf(ResourceNotFoundException.class);
        assertNoBooking();
    }
    @RepeatedTest(10) void slotUpdateWinsThenBookingUsesCommittedTimeAndPrice() throws Exception {
        assertThat(heldRace(()->master.saveSlot(slot,changedSlot()),()->booking.createBooking(request()))).isNull();
        assertThat(jdbc.queryForObject("SELECT price FROM booking_details WHERE court_id=?",BigDecimal.class,court)).isEqualByComparingTo("200000");
    }
    @RepeatedTest(10) void checkoutReplacementAlsoLocksItsNewSlot() throws Exception {
        UUID id=booking.createBooking(request()).getBookingId();
        UUID replacement=UUID.randomUUID();
        jdbc.update("INSERT INTO time_slots(id,start_time,end_time,price_multiplier) VALUES (?,'01:00','02:00',1)",replacement);
        try {
            assertThat(heldRace(()->modification.updateDetails(id,new UpdateBookingRequest(date,List.of(replacement))),()->master.deleteSlot(replacement)))
                    .isInstanceOf(BadRequestException.class);
        } finally {
            jdbc.update("DELETE FROM bookings WHERE id=?",id);
            jdbc.update("DELETE FROM time_slots WHERE id=?",replacement);
        }
    }
    void assertNoBooking() {assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings WHERE customer_id=?",Integer.class,owner)).isZero();}

    /** Independent committed transactions; the second must actually wait for a PostgreSQL row lock. */
    Throwable heldRace(Runnable first,Runnable second) throws Exception {
        var pool=Executors.newFixedThreadPool(2);var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        try {
            var a=pool.submit(()->new TransactionTemplate(transactions).execute(status->{first.run();held.countDown();await(release);return true;}));
            assertThat(held.await(10,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->{try {second.run();return null;}catch(RuntimeException e){return e;}});
            try {
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean blocked=false;
                while(System.nanoTime()<deadline) {
                    blocked=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE datname=current_database() AND cardinality(pg_blocking_pids(pid))>0)",Boolean.class));
                    if(blocked)break;
                    if(b.isDone())break;
                    Thread.sleep(20);
                }
                assertThat(blocked).as("second transaction waits on the first transaction's database lock").isTrue();
            } finally {release.countDown();}
            assertThat(a.get(10,TimeUnit.SECONDS)).isTrue();
            return b.get(10,TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdown();assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();}
    }
    static void await(CountDownLatch latch) {
        try {if(!latch.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Lock fixture timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
}
