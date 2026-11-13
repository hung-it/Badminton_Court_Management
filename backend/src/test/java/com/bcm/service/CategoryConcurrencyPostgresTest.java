package com.bcm.service;

import com.bcm.dto.request.ProductRequest;
import com.bcm.entity.ProductType;
import com.bcm.exception.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"booking.expiration-enabled=false","booking.auto-checkout.enabled=false"})
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class CategoryConcurrencyPostgresTest {
 @DynamicPropertySource static void db(DynamicPropertyRegistry r) {
  r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
  r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
  r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
 }
 @Autowired JdbcTemplate jdbc;@Autowired MasterDataService master;@Autowired PlatformTransactionManager transactions;
 @Test void deletionWinsAndConcurrentCreateIsRejected()throws Exception {race(false);}
 @Test void creationWinsAndConcurrentDeletionIsRejected()throws Exception {race(true);}
 private void race(boolean createFirst)throws Exception {
  UUID category=UUID.randomUUID();
  jdbc.update("insert into categories(id,category_name) values (?,?)",category,"Category race "+category);
  var request=new ProductRequest(category,"Concurrent service",ProductType.SERVICE,"item",BigDecimal.TEN,0,null,null);
  var pool=Executors.newSingleThreadExecutor();var started=new CountDownLatch(1);
  var future=new java.util.concurrent.atomic.AtomicReference<Future<?>>();
  try {
   new TransactionTemplate(transactions).executeWithoutResult(tx->{
    if(createFirst)master.saveProduct(null,request);else master.deleteCategory(category);
    future.set(pool.submit(()->{started.countDown();if(createFirst)master.deleteCategory(category);else master.saveProduct(null,request);}));
    try {assertThat(started.await(3,TimeUnit.SECONDS)).isTrue();assertThatThrownBy(()->future.get().get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}
    catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
   });
   assertThatThrownBy(()->future.get().get(5,TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
    .hasCauseInstanceOf(createFirst?BadRequestException.class:ResourceNotFoundException.class);
   assertThat(jdbc.queryForObject("select count(*) from products p join categories c on c.id=p.category_id where p.category_id=? and p.deleted_at is null and c.deleted_at is not null",Integer.class,category)).isZero();
  } finally {pool.shutdownNow();jdbc.update("delete from products where category_id=?",category);jdbc.update("delete from categories where id=?",category);}
 }
}
