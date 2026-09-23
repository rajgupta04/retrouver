package com.booking.auth;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

/**
 * JWT token creation and validation using JJWT.
 *
 * Token contains:
 *   - sub: user ID (UUID)
 *   - email: user email (for display/logging)
 *   - iat: issued at
 *   - exp: expiration (1 hour)
 *
 * Secret key: in production, this would come from environment variables
 * or a secrets manager. Hardcoded here for the case study scope.
 */
@Component
public class JwtService {

    // Minimum 256 bits for HS256. In production: externalize to config/env.
    private static final String SECRET = "retrouver-jwt-secret-key-must-be-at-least-256-bits-long-for-hs256";
    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    private static final long EXPIRATION_HOURS = 1;

    /**
     * Create a signed JWT for the given user.
     */
    public String generateToken(UUID userId, String email) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId.toString())
                .claim("email", email)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(EXPIRATION_HOURS, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /**
     * Validate a token and extract the user ID.
     * Throws JwtException if the token is expired, tampered, or malformed.
     */
    public UUID validateAndGetUserId(String token) {
        String subject = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
        return UUID.fromString(subject);
    }
}
