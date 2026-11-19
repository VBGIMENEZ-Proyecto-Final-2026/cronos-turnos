package ar.edu.um.cronos.adapters.out.security;

import ar.edu.um.cronos.TestKeys;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NimbusTokenIssuerTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Duration TTL = Duration.ofHours(1);
    private static final Duration REMEMBER = Duration.ofDays(30);

    private final NimbusTokenIssuer issuer = new NimbusTokenIssuer(TestKeys.privateKey(), TestKeys.publicKey(), TTL,
            REMEMBER, Clock.fixed(NOW, ZoneOffset.UTC));

    /** Un decodificador como el de atlas: solo RS256 con la clave pública (sin validar el tiempo, para fijar el reloj). */
    private static NimbusJwtDecoder decoder(RSAPublicKey key) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder;
    }

    private static User user(String login, Set<String> authorities) {
        return new User(7L, login, "$2a$10$hashQueNoDebeViajar", "Juan", "Pérez", "juan@ejemplo.test", null, "es",
                true, authorities, NOW, NOW);
    }

    @Test
    void firmaConRs256YLaClavePublicaLoVerifica() {
        String token = issuer.issue(user("juan", Set.of("ROLE_USER")), false);

        Jwt jwt = decoder(TestKeys.publicKey()).decode(token);
        assertThat(jwt.getHeaders()).containsEntry("alg", "RS256").containsEntry("typ", "JWT");
        assertThat(jwt.getSubject()).isEqualTo("juan");
        assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
    }

    @Test
    void lasAutoridadesVanSeparadasPorEspacioYOrdenadas() {
        String token = issuer.issue(user("ana", Set.of("ROLE_USER", "ROLE_ADMIN")), false);

        assertThat(decoder(TestKeys.publicKey()).decode(token).getClaimAsString("auth"))
                .isEqualTo("ROLE_ADMIN ROLE_USER");
    }

    @Test
    void laVigenciaEsLaNormalOLaLargaConRememberMe() {
        Jwt normal = decoder(TestKeys.publicKey()).decode(issuer.issue(user("juan", Set.of("ROLE_USER")), false));
        Jwt largo = decoder(TestKeys.publicKey()).decode(issuer.issue(user("juan", Set.of("ROLE_USER")), true));

        assertThat(normal.getIssuedAt()).isEqualTo(NOW);
        assertThat(normal.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(largo.getExpiresAt()).isEqualTo(NOW.plus(REMEMBER));
    }

    @Test
    void elTokenSoloLlevaCuatroClaimsYNadaDelUsuarioNiDelHash() {
        String token = issuer.issue(user("juan", Set.of("ROLE_USER")), false);

        Jwt jwt = decoder(TestKeys.publicKey()).decode(token);
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "auth", "iat", "exp");
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
        assertThat(payload).doesNotContain("hashQueNoDebeViajar").doesNotContain("ejemplo.test").doesNotContain("Pérez");
    }

    @Test
    void unTokenAlteradoSeRechaza() {
        String token = issuer.issue(user("juan", Set.of("ROLE_USER")), false);
        String[] partes = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(partes[1])).replace("ROLE_USER", "ROLE_ADMIN");
        String alterado = partes[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes())
                + "." + partes[2];

        assertThatThrownBy(() -> decoder(TestKeys.publicKey()).decode(alterado)).isInstanceOf(JwtException.class);
    }

    @Test
    void unTokenFirmadoConOtraClaveSeRechaza() {
        KeyPair otra = TestKeys.generate(2048);
        NimbusTokenIssuer ajeno = new NimbusTokenIssuer((RSAPrivateKey) otra.getPrivate(),
                (RSAPublicKey) otra.getPublic(), TTL, REMEMBER, Clock.fixed(NOW, ZoneOffset.UTC));
        String token = ajeno.issue(user("juan", Set.of("ROLE_USER")), false);

        assertThatThrownBy(() -> decoder(TestKeys.publicKey()).decode(token)).isInstanceOf(JwtException.class);
    }

    private static JwtTimestampValidator validatorAt(Instant instant) {
        JwtTimestampValidator validator = new JwtTimestampValidator(Duration.ZERO);
        validator.setClock(Clock.fixed(instant, ZoneOffset.UTC));
        return validator;
    }

    @Test
    void unTokenVencidoLoRechazaElValidadorDeTiempo() {
        String token = issuer.issue(user("juan", Set.of("ROLE_USER")), false);
        Jwt jwt = decoder(TestKeys.publicKey()).decode(token);

        // El mismo validador que usa atlas, sin tolerancia: una hora y un segundo después ya venció.
        assertThat(validatorAt(NOW.plus(TTL).plusSeconds(1)).validate(jwt).hasErrors()).isTrue();
        assertThat(validatorAt(NOW.plusSeconds(60)).validate(jwt).hasErrors()).isFalse();
    }
}
