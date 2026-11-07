package com.bcm.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Utility class for JWT token operations
 * - Generate access token and refresh token
 * - Validate tokens
 * - Extract claims from tokens
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret}")
    private String secret;

    @Value("${jwt.access-token-expiration}")
    private Long accessTokenExpiration;

    @Value("${jwt.refresh-token-expiration}")
    private Long refreshTokenExpiration;

    public static final String CLAIM_TOKEN_TYPE = "typ";
    public static final String TOKEN_TYPE_ACCESS = "ACCESS";
    public static final String TOKEN_TYPE_REFRESH = "REFRESH";
    public static final String CLAIM_USER_ID = "uid";
    public static final String CLAIM_ROLES = "roles";

    /**
     * Generate access token for authenticated user with typ, uid, and roles claims
     * @param userDetails Spring Security UserDetails
     * @return JWT access token
     */
    public String generateAccessToken(UserDetails userDetails) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TOKEN_TYPE, TOKEN_TYPE_ACCESS);

        if (userDetails instanceof com.bcm.security.UserPrincipal principal) {
            claims.put(CLAIM_USER_ID, principal.getId());
            java.util.List<String> roles = principal.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .toList();
            claims.put(CLAIM_ROLES, roles);
        } else if (userDetails != null && userDetails.getAuthorities() != null) {
            java.util.List<String> roles = userDetails.getAuthorities().stream()
                    .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                    .toList();
            claims.put(CLAIM_ROLES, roles);
        }

        return createToken(claims, userDetails.getUsername(), accessTokenExpiration);
    }

    /**
     * Generate refresh token with typ claim
     * @param email User email
     * @return JWT refresh token
     */
    public String generateRefreshToken(String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put(CLAIM_TOKEN_TYPE, TOKEN_TYPE_REFRESH);
        return createToken(claims, email, refreshTokenExpiration);
    }

    /**
     * Create JWT token with claims and subject
     */
    private String createToken(Map<String, Object> claims, String subject, Long expiration) {
        Date now = new Date();
        Date expiryDate = new Date(now.getTime() + expiration);

        return Jwts.builder()
                .claims(claims)
                .subject(subject)
                .issuedAt(now)
                .expiration(expiryDate)
                .signWith(getSignKey())
                .compact();
    }

    /**
     * Get signing key from secret
     */
    private SecretKey getSignKey() {
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * Extract email (subject) from token
     * @param token JWT token
     * @return Email
     */
    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    /**
     * Extract expiration date from token
     * @param token JWT token
     * @return Expiration date
     */
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Extract specific claim from token
     */
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    /**
     * Extract all claims from token
     */
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Check if token is expired
     */
    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * Validate token against user details
     * @param token JWT token
     * @param userDetails Spring Security UserDetails
     * @return true if valid
     */
    public Boolean validateToken(String token, UserDetails userDetails) {
        final String email = extractEmail(token);
        return (email.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    /**
     * Validate token (without user details)
     * Verifies signature via extractAllClaims() to reject tampered tokens,
     * then checks expiration. A token with a forged signature will fail
     * parseSignedClaims() and return false.
     * @param token JWT token
     * @return true if signature is valid and not expired
     */
    public Boolean validateToken(String token) {
        try {
            extractAllClaims(token);
            return !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Extract token type (typ claim)
     */
    public String extractTokenType(String token) {
        try {
            return extractClaim(token, claims -> claims.get(CLAIM_TOKEN_TYPE, String.class));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract user UUID from uid claim
     */
    public String extractUserId(String token) {
        try {
            return extractClaim(token, claims -> claims.get(CLAIM_USER_ID, String.class));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extract roles list from roles claim
     */
    @SuppressWarnings("unchecked")
    public java.util.List<String> extractRoles(String token) {
        try {
            return extractClaim(token, claims -> claims.get(CLAIM_ROLES, java.util.List.class));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Check if token is an ACCESS token
     * Accepts explicitly marked ACCESS tokens or tokens without typ claim (backward compatibility),
     * but strictly rejects REFRESH tokens.
     */
    public Boolean isAccessToken(String token) {
        String typ = extractTokenType(token);
        return !TOKEN_TYPE_REFRESH.equalsIgnoreCase(typ);
    }

    /**
     * Check if token is explicitly a REFRESH token
     */
    public Boolean isRefreshToken(String token) {
        String typ = extractTokenType(token);
        return TOKEN_TYPE_REFRESH.equalsIgnoreCase(typ);
    }
}
