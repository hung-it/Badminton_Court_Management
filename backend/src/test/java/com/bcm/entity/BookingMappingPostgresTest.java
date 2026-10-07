package com.bcm.entity;

import com.bcm.repository.BookingDetailRepository;
import com.bcm.repository.BookingRepository;
import com.bcm.repository.PaymentTransactionRepository;
import com.bcm.repository.RoleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requires a dedicated PostgreSQL database initialized with db/migration.sql.
 * Set BCM_TEST_DB_URL, BCM_TEST_DB_USERNAME and BCM_TEST_DB_PASSWORD to run.
 * Fixtures and persistence changes roll back after each test; schema is never generated.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "BCM_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class BookingMappingPostgresTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("BCM_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> Objects.requireNonNull(
                System.getenv("BCM_TEST_DB_USERNAME"), "BCM_TEST_DB_USERNAME is required"));
        registry.add("spring.datasource.password", () ->
                System.getenv().getOrDefault("BCM_TEST_DB_PASSWORD", ""));
    }

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private BookingDetailRepository details;

    @Autowired
    private PaymentTransactionRepository transactions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoleRepository roles;

    private UUID customerId;
    private UUID staffId;
    private UUID courtId;
    private UUID timeSlotId;

    @BeforeEach
    void insertReferenceFixtures() {
        UUID userId = UUID.randomUUID();
        customerId = UUID.randomUUID();
        staffId = UUID.randomUUID();
        courtId = UUID.randomUUID();
        timeSlotId = UUID.randomUUID();
        jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone) VALUES (?,?,?,'Booking fixture','0900000000')",
                userId, userId + "@mapping.test", "test-only");
        jdbc.update("INSERT INTO customers (id, user_id, full_name, phone) VALUES (?, ?, ?, ?)",
                customerId, userId, "Mapping customer", "0900000000");
        jdbc.update("INSERT INTO staffs (id, user_id, full_name, phone, position) VALUES (?, ?, ?, ?, ?)",
                staffId, userId, "Mapping staff", "0900000001", "Test");
        jdbc.update("""
                INSERT INTO courts (id, court_number, name, type, status, base_price)
                VALUES (?, ?, ?, ?, ?, ?)
                """, courtId, -1, "Mapping court", "Indoor", "AVAILABLE", new BigDecimal("123456.78"));
        jdbc.update("INSERT INTO time_slots (id, start_time, end_time, price_multiplier) VALUES (?, ?, ?, ?)",
                timeSlotId, java.sql.Time.valueOf("10:00:00"), java.sql.Time.valueOf("11:00:00"),
                new BigDecimal("1.25"));
    }

    @Test
    void looksUpSeededRolesWithOnlySchemaBackedPersistentAttributes() {
        assertThat(entityManager.getMetamodel().entity(Role.class).getAttributes())
                .extracting(attribute -> attribute.getName())
                .containsExactlyInAnyOrder("id", "roleName", "createdAt", "users");
        for (String name : new String[]{"ADMIN", "STAFF", "CUSTOMER"}) {
            Role role = roles.findByRoleName(name).orElseThrow();
            assertThat(role.getId()).isNotNull();
            assertThat(role.getRoleName()).isEqualTo(name);
            assertThat(role.getCreatedAt()).isNotNull();
        }
        assertThat(roles.findByRoleName("MISSING_ROLE")).isEmpty();
    }

    @Test
    void roundTripsRoleWithGeneratedUuidAndCreationTimestamp() {
        Role role = roles.saveAndFlush(new Role("TEST_" + UUID.randomUUID()));
        UUID id = role.getId();
        LocalDateTime createdAt = role.getCreatedAt();
        assertThat(id).isNotNull();
        assertThat(createdAt).isNotNull();
        entityManager.clear();

        Role loaded = roles.findByRoleName(role.getRoleName()).orElseThrow();
        assertThat(loaded.getId()).isEqualTo(id);
        assertThat(loaded.getCreatedAt()).isEqualTo(jdbc.queryForObject(
                "SELECT created_at FROM roles WHERE id=?", LocalDateTime.class, id));
        loaded.setRoleName("RENAMED_" + UUID.randomUUID());
        roles.saveAndFlush(loaded);
        entityManager.clear();
        Role renamed = roles.findByRoleName(loaded.getRoleName()).orElseThrow();
        assertThat(renamed.getId()).isEqualTo(id);
        assertThat(renamed.getCreatedAt()).isEqualTo(loaded.getCreatedAt());
    }

    @Test
    void roundTripsBookingAndDetailWithLazyReferencesAndAuditing() {
        Booking booking = newBooking();
        booking.setCreatedBy(entityManager.getReference(Staff.class, staffId));
        booking.setUpdatedBy(entityManager.getReference(Staff.class, staffId));
        bookings.saveAndFlush(booking);

        BookingDetail detail = new BookingDetail();
        detail.setBooking(booking);
        detail.setCourt(entityManager.getReference(Court.class, courtId));
        detail.setTimeSlot(entityManager.getReference(TimeSlot.class, timeSlotId));
        detail.setBookingDate(LocalDate.of(2030, 1, 15));
        detail.setPrice(new BigDecimal("154320.98"));
        details.saveAndFlush(detail);
        entityManager.clear();

        BookingDetail loaded = details.findById(detail.getId()).orElseThrow();
        var persistence = entityManager.getEntityManagerFactory().getPersistenceUnitUtil();
        assertThat(persistence.isLoaded(loaded, "booking")).isFalse();
        assertThat(persistence.isLoaded(loaded, "court")).isFalse();
        assertThat(persistence.isLoaded(loaded, "timeSlot")).isFalse();
        assertThat(loaded.getBookingDate()).isEqualTo(LocalDate.of(2030, 1, 15));
        assertThat(loaded.getPrice()).isEqualByComparingTo("154320.98");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getCourt().getId()).isEqualTo(courtId);
        assertThat(loaded.getCourt().getName()).isEqualTo("Mapping court");
        assertThat(loaded.getCourt().getStatus()).isEqualTo(CourtStatus.AVAILABLE);
        assertThat(loaded.getCourt().getBasePrice()).isEqualByComparingTo("123456.78");
        assertThat(loaded.getTimeSlot().getId()).isEqualTo(timeSlotId);
        assertThat(loaded.getTimeSlot().getStartTime()).isEqualTo(LocalTime.of(10, 0));
        assertThat(loaded.getTimeSlot().getEndTime()).isEqualTo(LocalTime.of(11, 0));
        assertThat(loaded.getTimeSlot().getPriceMultiplier()).isEqualByComparingTo("1.25");

        Booking loadedBooking = loaded.getBooking();
        assertThat(loadedBooking.getId()).isEqualTo(booking.getId());
        assertThat(loadedBooking.getCustomer().getId()).isEqualTo(customerId);
        assertThat(loadedBooking.getCreatedBy().getId()).isEqualTo(staffId);
        assertThat(loadedBooking.getUpdatedBy().getId()).isEqualTo(staffId);
        assertThat(loadedBooking.getCourtFee()).isEqualByComparingTo("154320.98");
        assertThat(loadedBooking.getExpiresAt()).isEqualTo(booking.getExpiresAt());
        assertThat(loadedBooking.getCreatedAt()).isNotNull();
        assertThat(loadedBooking.getUpdatedAt()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    void roundTripsBookingStatusesAndNullableStaffReferences(BookingStatus status) {
        Booking booking = newBooking();
        booking.setStatus(status);
        if (status != BookingStatus.PENDING) {
            booking.setExpiresAt(null);
        }
        bookings.saveAndFlush(booking);
        entityManager.clear();

        Booking loaded = bookings.findById(booking.getId()).orElseThrow();
        assertThat(loaded.getStatus()).isEqualTo(status);
        assertThat(loaded.getCreatedBy()).isNull();
        assertThat(loaded.getUpdatedBy()).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class,
                booking.getId())).isEqualTo(status.name());
    }

    @ParameterizedTest
    @EnumSource(PaymentMethod.class)
    void roundTripsPaymentMethodsAndNullableColumns(PaymentMethod method) {
        Booking booking = bookings.saveAndFlush(newBooking());
        PaymentTransaction payment = newPayment();
        payment.setBooking(booking);
        payment.setPaymentMethod(method);
        transactions.saveAndFlush(payment);
        entityManager.clear();

        PaymentTransaction loaded = transactions.findById(payment.getId()).orElseThrow();
        assertThat(loaded.getBooking().getId()).isEqualTo(booking.getId());
        assertThat(loaded.getInvoice()).isNull();
        assertThat(loaded.getPaymentMethod()).isEqualTo(method);
        assertThat(loaded.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(loaded.getTransactionId()).isNull();
        assertThat(loaded.getTransactionDate()).isNull();
        assertThat(loaded.getNote()).isNull();
        assertThat(loaded.getAmount()).isEqualByComparingTo("154320.98");
        assertThat(loaded.getCreatedAt()).isNotNull();
    }

    @ParameterizedTest
    @EnumSource(PaymentStatus.class)
    void roundTripsPaymentStatusesAndInvoiceReference(PaymentStatus status) {
        UUID invoiceId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invoices (id, customer_id, payment_method, total_amount)
                VALUES (?, ?, ?, ?)
                """, invoiceId, customerId, "CASH", new BigDecimal("154320.98"));
        PaymentTransaction payment = newPayment();
        payment.setInvoice(entityManager.getReference(Invoice.class, invoiceId));
        payment.setStatus(status);
        payment.setTransactionId(UUID.randomUUID().toString());
        payment.setTransactionDate(LocalDateTime.of(2030, 1, 15, 9, 30, 1));
        payment.setNote("PostgreSQL text mapping ".repeat(30));
        transactions.saveAndFlush(payment);
        entityManager.clear();

        PaymentTransaction loaded = transactions.findById(payment.getId()).orElseThrow();
        assertThat(loaded.getBooking()).isNull();
        assertThat(loaded.getInvoice().getId()).isEqualTo(invoiceId);
        assertThat(loaded.getStatus()).isEqualTo(status);
        assertThat(loaded.getTransactionId()).isEqualTo(payment.getTransactionId());
        assertThat(loaded.getTransactionDate()).isEqualTo(payment.getTransactionDate());
        assertThat(loaded.getNote()).isEqualTo(payment.getNote());
    }

    private Booking newBooking() {
        Booking booking = new Booking();
        booking.setCustomer(entityManager.getReference(Customer.class, customerId));
        booking.setCourtFee(new BigDecimal("154320.98"));
        booking.setExpiresAt(LocalDateTime.of(2030, 1, 15, 9, 45));
        return booking;
    }

    private PaymentTransaction newPayment() {
        PaymentTransaction payment = new PaymentTransaction();
        payment.setPaymentMethod(PaymentMethod.VNPAY);
        payment.setAmount(new BigDecimal("154320.98"));
        return payment;
    }
}
