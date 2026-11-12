package com.bcm;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
@ActiveProfiles(value="postgres",inheritProfiles=false)
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class AdminCustomerPostgresTest extends AdminCustomerIntegrationTest {
 @DynamicPropertySource static void database(DynamicPropertyRegistry r){
  r.add("bcm.seed.enabled",()->"false");
  r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
  r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
  r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
 }
}
