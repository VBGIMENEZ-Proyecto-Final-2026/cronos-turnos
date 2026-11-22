package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogLevels;
import ar.edu.um.cronos.TestKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login de punta a punta: registro real, hash real, token real verificado con la clave pública, PostgreSQL real. */
// Mismos niveles de log altos que en RegistrationApiTest (ver el comentario ahí): sin ellos la prueba de logs sería vacía.
@SpringBootTest(properties = {
        "logging.level.org.springframework.security=TRACE",
        "logging.level.org.hibernate.SQL=DEBUG",
        "logging.level.org.hibernate.orm.jdbc.bind=TRACE",
        "logging.level.ar.edu.um.cronos=TRACE",
        "logging.level.org.springframework.test=INFO"})
@AutoConfigureMockMvc
@IntegrationTestSupport
@ExtendWith(OutputCaptureExtension.class)
class AuthenticationApiTest {

    private static final String SECRETA = "clave-muy-secreta-123";

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        LogLevels.altos();
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    private void registrar(String login) throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\",\"password\":\"" + SECRETA + "\"}"))
                .andExpect(status().isCreated());
    }

    private ResultActions login(String username, String password, String extra) throws Exception {
        return mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"" + extra + "}"));
    }

    private static Jwt verificar(String token) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(TestKeys.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256).build();
        decoder.setJwtValidator(jwt -> OAuth2TokenValidatorResult.success());
        return decoder.decode(token);
    }

    private String idToken(ResultActions result) throws Exception {
        MvcResult mvcResult = result.andExpect(status().isOk()).andReturn();
        String body = mvcResult.getResponse().getContentAsString();
        String token = body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");
        assertThat(mvcResult.getResponse().getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer " + token);
        return token;
    }

    @Test
    void conCredencialesCorrectasDevuelveElTokenEnElCuerpoYEnElEncabezado() throws Exception {
        registrar("juan.perez");

        Jwt jwt = verificar(idToken(login("juan.perez", SECRETA, "")));

        assertThat(jwt.getSubject()).isEqualTo("juan.perez");
        assertThat(jwt.getClaimAsString("auth")).isEqualTo("ROLE_USER");
        assertThat(jwt.getExpiresAt()).isBetween(Instant.now().plus(Duration.ofMinutes(59)),
                Instant.now().plus(Duration.ofMinutes(61)));
    }

    @Test
    void conRememberMeLaVigenciaEsDeTreintaDias() throws Exception {
        registrar("juan");

        Jwt jwt = verificar(idToken(login("juan", SECRETA, ",\"rememberMe\":true")));

        assertThat(jwt.getExpiresAt()).isBetween(Instant.now().plus(Duration.ofDays(30)).minusSeconds(60),
                Instant.now().plus(Duration.ofDays(30)).plusSeconds(60));
    }

    @Test
    void elLoginNoDistingueMayusculasYElSubEsElLoginEnMinusculas() throws Exception {
        registrar("Juan.Perez");

        assertThat(verificar(idToken(login("JUAN.PEREZ", SECRETA, ""))).getSubject()).isEqualTo("juan.perez");
    }

    @Test
    void laRespuestaNoIncluyeNadaMasQueElToken() throws Exception {
        registrar("juan");

        String cuerpo = login("juan", SECRETA, "").andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).startsWith("{\"id_token\":\"").doesNotContain("password").doesNotContain("$2")
                .doesNotContain(SECRETA);
    }

    @Test
    void unaContrasenaEquivocadaYUnLoginInexistenteDanElMismo401() throws Exception {
        registrar("juan");

        String equivocada = login("juan", "otra-clave-1", "").andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String inexistente = login("nadie", "otra-clave-1", "").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        assertThat(inexistente).isEqualTo(equivocada);
        assertThat(equivocada).doesNotContain("juan").doesNotContain("otra-clave-1");
    }

    @Test
    void unUsuarioNoActivoNoPuedeIniciarSesion() throws Exception {
        registrar("juan");
        jdbc.update("UPDATE app_user SET activated = false WHERE login = 'juan'");

        login("juan", SECRETA, "").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void unaInyeccionSqlEnElLoginSoloDa401() throws Exception {
        registrar("juan");

        login("juan' OR '1'='1", SECRETA, "").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class)).isEqualTo(1);
    }

    @Test
    void validaElCuerpoDelLogin() throws Exception {
        for (String json : new String[] {"{}", "{\"username\":\"juan\"}", "{\"password\":\"abcd\"}",
                "{\"username\":\"\",\"password\":\"abcd\"}", "{\"username\":\"juan\",\"password\":\"abc\"}",
                "{\"username\":\"juan\",\"password\":\"" + "a".repeat(101) + "\"}",
                "{\"username\":\"" + "u".repeat(255) + "\",\"password\":\"abcd\"}", "no es json"}) {
            mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON).content(json))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void unaContrasenaCortaEnElLoginNoSeEtiquetaComoInvalidPassword() throws Exception {
        // INVALID_PASSWORD es del registro (referencia 13.1); el login responde VALIDATION_ERROR.
        login("juan", "abc", "").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void soloElPostEstaAbierto() throws Exception {
        // Sin token, una ruta no habilitada responde 401 (no autenticado), no 403.
        mvc.perform(get("/api/authenticate")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));
    }

    @Test
    void elLoginDejaSuEventoConElIdYSinElNombreDeUsuario() throws Exception {
        registrar("juan");
        try (var log = new ar.edu.um.cronos.LogCapture()) {
            login("juan", SECRETA, ",\"rememberMe\":true").andExpect(status().isOk());
            login("juan", "otra-clave-1", "").andExpect(status().isUnauthorized());
            login("nadie", "otra-clave-1", "").andExpect(status().isUnauthorized());

            long id = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'juan'", Long.class);
            assertThat(log.eventos("usuario.login.ok")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).isEqualTo("evento=usuario.login.ok usuario=" + id + " recordarme=true"));
            assertThat(log.eventos("usuario.login.fallido")).extracting(e -> e.getFormattedMessage()).containsExactly(
                    "evento=usuario.login.fallido usuario=" + id + " motivo=clave_incorrecta",
                    "evento=usuario.login.fallido usuario=- motivo=usuario_inexistente");
            // El nombre que se intentó no se escribe nunca: puede ser un dato personal o una carga hostil.
            assertThat(log.list().stream().map(e -> e.getFormattedMessage()).filter(m -> m.startsWith("evento=")))
                    .isNotEmpty().noneMatch(m -> m.contains("juan") || m.contains("nadie"));
        }
    }

    @Test
    void laContrasenaNiElTokenAparecenEnLosLogs(CapturedOutput output) throws Exception {
        registrar("juan");
        String token = idToken(login("juan", SECRETA, ""));
        login("juan", SECRETA + "x", "").andExpect(status().isUnauthorized());
        login("nadie", SECRETA, "").andExpect(status().isUnauthorized());

        assertThat(output.getAll()).isNotEmpty().doesNotContain(SECRETA).doesNotContain(token);
    }
}
