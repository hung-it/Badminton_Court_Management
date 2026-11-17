package com.bcm.service;

import com.bcm.dto.request.CourtRequest;
import com.bcm.entity.*;
import com.bcm.exception.*;
import com.bcm.repository.CourtRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Connection-local fixtures only: no public runtime data is changed. */
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class CourtBranchAssignmentPostgresTest {
    private final UUID branch=UUID.randomUUID(), otherBranch=UUID.randomUUID();
    private Connection connection;
    private JdbcTemplate jdbc;
    private CourtRepository courts;
    private MasterDataService service;

    @BeforeEach void fixture() throws Exception {
        connection=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));
        connection.setAutoCommit(false);
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
        jdbc.execute("CREATE TEMP TABLE branches(id uuid, district text,address text,active boolean,deleted_at timestamp)");
        jdbc.execute("CREATE TEMP TABLE courts(id uuid,court_code integer,branch_id uuid,court_number integer,deleted_at timestamp)");
        jdbc.update("INSERT INTO pg_temp.branches VALUES (?,'Bình Thạnh','Địa chỉ chi nhánh',true,null),(?,'Quận 1','Chi nhánh khác',true,null)",branch,otherBranch);
        jdbc.update("INSERT INTO pg_temp.courts VALUES (?,999,?,1,null)",UUID.randomUUID(),otherBranch);
        courts=mock(CourtRepository.class);
        when(courts.saveAndFlush(any(Court.class))).thenAnswer(call->call.getArgument(0));
        service=new MasterDataService(courts,null,null,null,null,jdbc);
    }
    @AfterEach void close() throws Exception {if(connection!=null){connection.rollback();connection.close();}}
    private CourtRequest request(UUID branchId,int number){return new CourtRequest(number,"Tên sân",CourtType.WOODEN_FLOOR,CourtStatus.AVAILABLE,new BigDecimal("123456"),"Quận nhập sai","Địa chỉ nhập sai",branchId);}

    @Test void localOneInAnotherBranchGetsItsOwnGlobalCodeAndCanonicalLocation(){
        Court saved=service.saveCourt(null,request(branch,1));
        assertThat(saved.getBranchId()).isEqualTo(branch);
        assertThat(saved.getBranchCourtNumber()).isEqualTo(1);
        assertThat(saved.getCourtCode()).isEqualTo(1000);
        assertThat(saved.getDistrict()).isEqualTo("Bình Thạnh");
        assertThat(saved.getAddress()).isEqualTo("Địa chỉ chi nhánh");
        assertThat(saved.getType()).isEqualTo(CourtType.WOODEN_FLOOR);
    }
    @Test void localNumbersAboveSixAreSupported(){
        assertThat(service.saveCourt(null,request(branch,7)).getBranchCourtNumber()).isEqualTo(7);
    }
    @Test void rejectsOccupiedNumberInSameBranch(){
        assertThatThrownBy(()->service.saveCourt(null,request(otherBranch,1))).isInstanceOf(DuplicateResourceException.class);
        verify(courts,never()).saveAndFlush(any());
    }
    @Test void deletedCourtDoesNotReserveItsLocalNumber(){
        jdbc.update("UPDATE pg_temp.courts SET deleted_at=CURRENT_TIMESTAMP");
        assertThat(service.saveCourt(null,request(otherBranch,1)).getCourtCode()).isEqualTo(1000);
    }
    @Test void rejectsInactiveOrMissingBranch(){
        jdbc.update("UPDATE pg_temp.branches SET active=false WHERE id=?",branch);
        assertThatThrownBy(()->service.saveCourt(null,request(branch,1))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->service.saveCourt(null,request(UUID.randomUUID(),1))).isInstanceOf(BadRequestException.class);
    }
    @Test void updatePreservesGlobalCodeAndDoesNotMoveHistoricalCourt(){
        Court existing=new Court();existing.setId(UUID.randomUUID());existing.setBranchId(branch);existing.setBranchCourtNumber(1);existing.setCourtCode(22);existing.setStatus(CourtStatus.AVAILABLE);
        when(courts.findAllForBookingWithLock(List.of(existing.getId()))).thenReturn(List.of(existing));
        assertThat(service.saveCourt(existing.getId(),request(branch,1)).getCourtCode()).isEqualTo(22);
        assertThatThrownBy(()->service.saveCourt(existing.getId(),request(otherBranch,2))).isInstanceOf(BadRequestException.class);
    }
    @Test void allocationLockIsHeldUntilTransactionFinishes() throws Exception {
        service.saveCourt(null,request(branch,1));
        try(var second=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"))) {
            second.setAutoCommit(false);
            var otherJdbc=new JdbcTemplate(new SingleConnectionDataSource(second,true));
            assertThat(otherJdbc.queryForObject("SELECT pg_try_advisory_xact_lock(20261117,1)",Boolean.class)).isFalse();
            connection.rollback();
            assertThat(otherJdbc.queryForObject("SELECT pg_try_advisory_xact_lock(20261117,1)",Boolean.class)).isTrue();
            second.rollback();
        }
    }
    @Test void canonicalMigrationSupportsExtraCourtsAndReusingDeletedNumbers() throws Exception {
        jdbc.execute("ALTER TABLE courts ADD CONSTRAINT chk_court_number_range CHECK(court_number BETWEEN 1 AND 6)");
        jdbc.execute("CREATE UNIQUE INDEX uq_court_branch_number ON courts(branch_id,court_number)");
        String sql=java.nio.file.Files.readString(java.nio.file.Path.of("../db/upgrades/booking-ux-upgrade.sql"));
        String numbers=sql.substring(sql.indexOf("-- BEGIN SECTION: court-numbers"),sql.indexOf("-- END SECTION: court-numbers"));
        String index=sql.lines().filter(line->line.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS uq_court_branch_number_active")||line.startsWith("DROP INDEX IF EXISTS uq_court_branch_number;")).collect(java.util.stream.Collectors.joining("\n"));
        for(int i=0;i<2;i++){jdbc.execute(numbers);jdbc.execute(index);}
        jdbc.update("INSERT INTO pg_temp.courts VALUES (?,1000,?,7,null)",UUID.randomUUID(),branch);
        var duplicate=connection.setSavepoint();
        assertThatThrownBy(()->jdbc.update("INSERT INTO pg_temp.courts VALUES (?,1001,?,7,null)",UUID.randomUUID(),branch)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        connection.rollback(duplicate);
        jdbc.update("UPDATE pg_temp.courts SET deleted_at=CURRENT_TIMESTAMP WHERE branch_id=?",branch);
        jdbc.update("INSERT INTO pg_temp.courts VALUES (?,1001,?,7,null)",UUID.randomUUID(),branch);
        var zero=connection.setSavepoint();
        assertThatThrownBy(()->jdbc.update("INSERT INTO pg_temp.courts VALUES (?,1002,?,0,null)",UUID.randomUUID(),branch)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        connection.rollback(zero);
        assertThat(jdbc.queryForObject("SELECT court_code FROM pg_temp.courts WHERE branch_id=?",Integer.class,otherBranch)).isEqualTo(999);
    }
}
