package com.mbsc.finapp.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Generation et validation des JSON Web Tokens (HS256).
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    /** Cle par defaut livree avec le code : ne doit JAMAIS servir en production. */
    private static final String DEFAULT_SECRET =
        "Y2hhbmdlLW1lLXdpdGgtYS1sb25nLXJhbmRvbS1iYXNlNjQta2V5LWZvci1tYnNjLWZpbmFwcC0yMDI2";

    private final SecretKey signingKey;
    private final long expirationMs;
    private final long refreshExpirationMs;
    private final String issuer;

    public JwtService(
        @Value("${app.jwt.secret}") String secret,
        @Value("${app.jwt.expiration}") long expirationMs,
        @Value("${app.jwt.refresh-expiration}") long refreshExpirationMs,
        @Value("${app.jwt.issuer}") String issuer,
        @Value("${app.jwt.require-custom-secret:false}") boolean requireCustomSecret
    ) {
        if (DEFAULT_SECRET.equals(secret)) {
            if (requireCustomSecret) {
                // Profil production : demarrage refuse avec la cle par defaut.
                throw new IllegalStateException(
                    "La cle JWT par defaut est interdite en production. "
                    + "Definissez APP_JWT_SECRET (openssl rand -base64 48).");
            }
            log.warn("=== ATTENTION : la cle JWT par defaut est utilisee. "
                + "Definissez APP_JWT_SECRET (openssl rand -base64 48) en production. ===");
        }
        this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
        this.expirationMs = expirationMs;
        this.refreshExpirationMs = refreshExpirationMs;
        this.issuer = issuer;
    }

    /** Valeurs du claim "type" : distingue les jetons d'accès des jetons de rafraîchissement. */
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    public String generateAccessToken(UserPrincipal principal) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("uid", principal.getId());
        claims.put("type", TYPE_ACCESS);
        claims.put("roles", principal.getAuthorities().stream()
            .map(a -> a.getAuthority())
            .collect(Collectors.toList()));
        return buildToken(claims, principal.getUsername(), expirationMs);
    }

    public String generateRefreshToken(UserPrincipal principal) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("type", TYPE_REFRESH);
        return buildToken(claims, principal.getUsername(), refreshExpirationMs);
    }

    /**
     * Type du jeton ("access" / "refresh"). Les anciens jetons d'accès sans
     * claim de type sont traités comme des jetons d'accès.
     */
    public String extractTokenType(String token) {
        String type = extractClaim(token, c -> c.get("type", String.class));
        return type == null ? TYPE_ACCESS : type;
    }

    public boolean isAccessToken(String token) {
        try {
            return TYPE_ACCESS.equals(extractTokenType(token));
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isRefreshToken(String token) {
        try {
            return TYPE_REFRESH.equals(extractTokenType(token));
        } catch (Exception e) {
            return false;
        }
    }

    private String buildToken(Map<String, Object> claims, String subject, long ttlMs) {
        Date now = new Date();
        return Jwts.builder()
            .claims(claims)
            .subject(subject)
            .issuer(issuer)
            .issuedAt(now)
            .expiration(new Date(now.getTime() + ttlMs))
            .signWith(signingKey)
            .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isTokenExpired(String token) {
        return extractClaim(token, Claims::getExpiration).before(new Date());
    }

    public <T> T extractClaim(String token, Function<Claims, T> resolver) {
        return resolver.apply(extractAllClaims(token));
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .requireIssuer(issuer)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public long getAccessTokenExpirationMs() {
        return expirationMs;
    }
}
