package com.bcm;

import com.bcm.security.UserPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import java.util.UUID;

/** Real test-only reference identities. No bypass of CurrentStaffService or account checks. */
public final class TestActors {
    private TestActors() { }

    public static UserPrincipal admin(JdbcTemplate jdbc) {
        var rows=jdbc.queryForList("SELECT u.id,u.email FROM users u JOIN user_roles ur ON ur.user_id=u.id JOIN roles r ON r.id=ur.role_id WHERE r.role_name='ADMIN' AND u.deleted_at IS NULL ORDER BY u.email");
        if(rows.isEmpty())throw new IllegalStateException("Test database needs a seeded admin");
        return new UserPrincipal(rows.get(0).get("id").toString(),rows.get(0).get("email").toString(),"unused",List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),true);
    }

    public static UserPrincipal staff(JdbcTemplate jdbc) {
        UUID user=UUID.fromString("00000000-0000-4000-8000-00000000b100"),staff=UUID.fromString("00000000-0000-4000-8000-00000000b101");
        if(jdbc.queryForObject("SELECT count(*) FROM users WHERE id=?",Integer.class,user)==0) {
            jdbc.update("INSERT INTO users(id,email,password_hash,full_name,phone,created_at,updated_at) VALUES (?,'regression.staff@example.test','!','Regression staff','0912345678',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",user);
            jdbc.update("INSERT INTO user_roles(user_id,role_id) SELECT ?,id FROM roles WHERE role_name='STAFF'",user);
            jdbc.update("INSERT INTO staffs(id,user_id,full_name,phone,position,created_at,updated_at) VALUES (?,?,'Regression staff','0912345678','Cashier',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",staff,user);
        }
        return new UserPrincipal(user.toString(),"regression.staff@example.test","unused",List.of(new SimpleGrantedAuthority("ROLE_STAFF")),true);
    }

    public static void authenticate(UserPrincipal actor) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor,null,actor.getAuthorities()));
    }
}
