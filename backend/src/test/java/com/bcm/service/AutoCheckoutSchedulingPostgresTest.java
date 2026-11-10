package com.bcm.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"booking.expiration-enabled=false","booking.auto-checkout.enabled=true","booking.auto-checkout.interval=PT1H","bcm.seed.enabled=false"})
@EnabledIfEnvironmentVariable(named="BCM_TEST_DB_URL",matches="jdbc:postgresql:.*")
class AutoCheckoutSchedulingPostgresTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("BCM_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv("BCM_TEST_DB_USERNAME"));
        r.add("spring.datasource.password",()->System.getenv("BCM_TEST_DB_PASSWORD"));
    }
    @Autowired BookingAutoCheckoutJob job;
    @Autowired CheckoutService checkout;
    @Autowired ScheduledAnnotationBeanPostProcessor scheduling;

    @Test void enabledJobIsRegisteredWithTransactionalCheckoutProxy() {
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(checkout)).isTrue();
        assertThat(scheduling.getScheduledTasks()).anySatisfy(task->{
            assertThat(task.getTask().getRunnable()).isInstanceOf(ScheduledMethodRunnable.class);
            var runnable=(ScheduledMethodRunnable)task.getTask().getRunnable();
            assertThat(runnable.getTarget()).isSameAs(job);
            assertThat(runnable.getMethod().getName()).isEqualTo("run");
        });
    }
}
