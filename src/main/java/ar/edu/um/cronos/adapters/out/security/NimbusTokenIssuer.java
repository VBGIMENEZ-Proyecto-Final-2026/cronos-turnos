package ar.edu.um.cronos.adapters.out.security;

import ar.edu.um.cronos.application.port.out.TokenIssuer;
import ar.edu.um.cronos.domain.User;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Emite JWT firmados con RS256 y la clave privada de cronos. El token lleva solo cuatro claims: {@code sub} (el
 * login), {@code auth} (las autoridades separadas por espacio, como el token de JHipster: es lo que atlas lee),
 * {@code iat} y {@code exp}. No lleva email, nombres ni nada del hash: el token viaja en cada petición.
 */
public class NimbusTokenIssuer implements TokenIssuer {

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration ttl;
    private final Duration rememberMeTtl;

    public NimbusTokenIssuer(RSAPrivateKey privateKey, RSAPublicKey publicKey, Duration ttl, Duration rememberMeTtl,
                             Clock clock) {
        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey(privateKey).build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        this.ttl = ttl;
        this.rememberMeTtl = rememberMeTtl;
        this.clock = clock;
    }

    @Override
    public String issue(User user, boolean rememberMe) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(user.login())
                .claim("auth", String.join(" ", user.authorities().stream().sorted().toList()))
                .issuedAt(now)
                .expiresAt(now.plus(rememberMe ? rememberMeTtl : ttl))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
