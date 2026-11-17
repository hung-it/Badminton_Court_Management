package com.bcm.service;

import com.bcm.dto.request.CourtBatchRequest;
import com.bcm.entity.*;
import com.bcm.exception.*;
import com.bcm.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real PostgreSQL transactions over connection-local TEMP fixtures only. */
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class CourtBatchPostgresTest {
    Connection connection;JdbcTemplate jdbc;CourtRepository courts;CourtBatchService batch;
    final UUID existingId=UUID.randomUUID();
    @BeforeEach void fixture() throws Exception {
        connection=DriverManager.getConnection(System.getenv("BCM_TEST_DB_URL"),System.getenv("BCM_TEST_DB_USERNAME"),System.getenv("BCM_TEST_DB_PASSWORD"));
        var ds=new SingleConnectionDataSource(connection,true);jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TEMP TABLE branches(id uuid PRIMARY KEY,code text UNIQUE,name text,district text,address text,active boolean,deleted_at timestamp)");
        jdbc.execute("CREATE TEMP TABLE courts(id uuid PRIMARY KEY,court_code integer UNIQUE,branch_id uuid REFERENCES branches(id),court_number integer CHECK(court_number>0),name text,type text,status text,base_price numeric,district text,address text,deleted_at timestamp)");
        jdbc.execute("CREATE UNIQUE INDEX ON courts(branch_id,court_number) WHERE deleted_at IS NULL");
        jdbc.update("INSERT INTO pg_temp.branches VALUES (?,'EXISTING','Chi nhánh cũ','Bình Thạnh','Địa chỉ cũ',true,null)",existingId);
        var branches=mock(BranchRepository.class);courts=mock(CourtRepository.class);
        when(branches.saveAndFlush(any(Branch.class))).thenAnswer(call->{
            Branch b=call.getArgument(0);b.setId(UUID.randomUUID());
            jdbc.update("INSERT INTO pg_temp.branches VALUES (?,?,?,?,?,true,null)",b.getId(),b.getCode(),b.getName(),b.getDistrict(),b.getAddress());return b;
        });
        when(courts.saveAndFlush(any(Court.class))).thenAnswer(call->{
            Court c=call.getArgument(0);c.setId(UUID.randomUUID());
            jdbc.update("INSERT INTO pg_temp.courts VALUES (?,?,?,?,?,?,?,?,?,?,null)",c.getId(),c.getCourtCode(),c.getBranchId(),c.getBranchCourtNumber(),c.getName(),c.getType().name(),c.getStatus().name(),c.getBasePrice(),c.getDistrict(),c.getAddress());return c;
        });
        var master=new MasterDataService(courts,null,null,null,null,jdbc);
        var proxy=new ProxyFactory(new CourtBatchService(branches,master,jdbc));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(ds),new AnnotationTransactionAttributeSource()));
        batch=(CourtBatchService)proxy.getProxy();
    }
    @AfterEach void close() throws Exception {if(connection!=null)connection.close();}
    CourtBatchRequest request(UUID id,int count){return new CourtBatchRequest(id,"Chi nhánh mới","Bình Thạnh","Địa chỉ mới",count,CourtType.WOODEN_FLOOR,CourtStatus.AVAILABLE,new BigDecimal("150000"));}
    @Test void createsNewBranchAndFifteenSequentialCourts(){
        var created=batch.create(request(null,15));assertThat(created).hasSize(15);
        UUID branch=created.get(0).getBranchId();assertThat(branch).isNotEqualTo(existingId);
        assertThat(created).extracting(Court::getBranchCourtNumber).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,15).boxed().toList());
        assertThat(created).extracting(Court::getCourtCode).doesNotHaveDuplicates();
        assertThat(created.get(0).getName()).isEqualTo("Chi nhánh mới · Sân 01");assertThat(created.get(14).getName()).isEqualTo("Chi nhánh mới · Sân 15");
        assertThat(created).allSatisfy(c->{assertThat(c.getDistrict()).isEqualTo("Bình Thạnh");assertThat(c.getAddress()).isEqualTo("Địa chỉ mới");assertThat(c.getType()).isEqualTo(CourtType.WOODEN_FLOOR);assertThat(c.getBasePrice()).isEqualByComparingTo("150000");});
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_temp.courts",Integer.class)).isEqualTo(15);
    }
    @Test void appendsToExistingBranchAndUsesItsCanonicalLocation(){
        batch.create(request(existingId,10));var more=batch.create(request(existingId,2));
        assertThat(more).extracting(Court::getBranchCourtNumber).containsExactly(11,12);
        assertThat(more.get(0).getName()).isEqualTo("Chi nhánh cũ · Sân 11");assertThat(more.get(0).getAddress()).isEqualTo("Địa chỉ cũ");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_temp.branches",Integer.class)).isEqualTo(1);
    }
    @Test void failureOnEighthCourtRollsBackNewBranchAndAllSevenEarlierCourts(){
        doAnswer(call->{Court c=call.getArgument(0);if(c.getBranchCourtNumber()==8)throw new org.springframework.dao.DataIntegrityViolationException("simulated persistence failure");
            jdbc.update("INSERT INTO pg_temp.courts(id,court_code,branch_id,court_number) VALUES (?,?,?,?)",UUID.randomUUID(),c.getCourtCode(),c.getBranchId(),c.getBranchCourtNumber());return c;}).when(courts).saveAndFlush(any());
        var newDistrict=new CourtBatchRequest(null,"Chi nhánh Bình Tân","Bình Tân","Địa chỉ mới",15,CourtType.STANDARD_MAT,CourtStatus.AVAILABLE,BigDecimal.TEN);
        assertThatThrownBy(()->batch.create(newDistrict)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_temp.branches",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_temp.branches WHERE district='Bình Tân'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_temp.courts",Integer.class)).isZero();
    }
    @Test void rejectsDuplicateNewBranch(){
        batch.create(request(null,1));assertThatThrownBy(()->batch.create(request(null,1))).isInstanceOf(DuplicateResourceException.class);
    }
    @Test void newDistrictIsSavedWithBranchAndReusedWithoutDuplicateLabels(){
        var request=new CourtBatchRequest(null,"Chi nhánh Bình Tân","  Bình   Tân  ","Địa chỉ mới",15,CourtType.STANDARD_MAT,CourtStatus.AVAILABLE,BigDecimal.TEN);
        assertThat(batch.create(request)).hasSize(15).allSatisfy(c->assertThat(c.getDistrict()).isEqualTo("Bình Tân"));
        var next=new CourtBatchRequest(null,"Chi nhánh thứ hai","quận bình tân","Địa chỉ khác",1,CourtType.STANDARD_MAT,CourtStatus.AVAILABLE,BigDecimal.TEN);
        assertThat(batch.create(next).get(0).getDistrict()).isEqualTo("Bình Tân");
        assertThat(jdbc.queryForList("SELECT DISTINCT district FROM pg_temp.branches WHERE district<>'Bình Thạnh'",String.class)).containsExactly("Bình Tân");
    }
    @Test void rejectsInactiveBranchAndInvalidQuantitiesWithoutWriting(){
        jdbc.update("UPDATE pg_temp.branches SET active=false");
        assertThatThrownBy(()->batch.create(request(existingId,15))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->batch.create(request(existingId,0))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(()->batch.create(request(existingId,1001))).isInstanceOf(BadRequestException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_temp.courts",Integer.class)).isZero();
    }
}
