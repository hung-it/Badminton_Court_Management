package com.bcm.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * P2.5: the startup guard queries the exact PosService guest definition and never throws.
 * No Mockito: a JdbcTemplate subclass recording executed SQL keeps the test light and deterministic.
 */
class WalkInGuestGuardTest {
    private static final String GUEST_QUERY = "SELECT count(*) FROM customers c JOIN users u ON u.id = c.user_id "
            + "WHERE u.email = 'guest@bcm.com' AND c.phone = '0000000000' "
            + "AND c.deleted_at IS NULL AND u.deleted_at IS NULL";

    private final List<String> executedSql = new ArrayList<>();
    private final JdbcTemplate fakeJdbc = new JdbcTemplate() {
        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            executedSql.add(sql);
            return requiredType.cast(1);
        }
    };

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void guardNeverThrows(boolean branch) {
        assertThatCode(() -> new WalkInGuestGuard(fakeJdbc).run(null)).doesNotThrowAnyException();
    }

    @Test
    void guardQueriesTheExactGuestDefinition() {
        new WalkInGuestGuard(fakeJdbc).run(null);
        assertThat(executedSql).containsExactly(GUEST_QUERY);
    }
}
