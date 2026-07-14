package com.example.server.auth;

import com.example.server.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long expirationMillis;

    public JwtUtil(@Value("${jwt.secret}") String secret,
                   @Value("${jwt.expiration}") long expirationMillis) {
        byte[] secretBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalArgumentException("jwt.secret must contain at least 32 UTF-8 bytes");
        }
        if (expirationMillis <= 0) {
            throw new IllegalArgumentException("jwt.expiration must be greater than 0");
        }
        this.signingKey = Keys.hmacShaKeyFor(secretBytes);
        this.expirationMillis = expirationMillis;
    }

    public String generateToken(User user) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("user id is required");
        }

        Date issuedAt = new Date();
        Date expiration = new Date(issuedAt.getTime() + expirationMillis);
        var builder = Jwts.builder()
                .subject(String.valueOf(user.getId()))
                .claim("userId", user.getId())
                .issuedAt(issuedAt)
                .expiration(expiration);
        if (user.getUsername() != null && !user.getUsername().isBlank()) {
            builder.claim("username", user.getUsername());
        }
        return builder.signWith(signingKey).compact();
    }

    public Claims parseAndValidate(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtException("token is empty");
        }
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public Long getUserId(Claims claims) {
        Object value = claims.get("userId");
        if (!(value instanceof Number number)) {
            throw new JwtException("userId claim is missing");
        }
        return number.longValue();
    }
}
