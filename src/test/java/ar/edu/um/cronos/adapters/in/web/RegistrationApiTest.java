package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogLevels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registro de punta a punta: HTTP, validaciones, seguridad y PostgreSQL real. */
// Niveles de log altos para que la prueba de logs no sea vacía: con la configuración normal estas peticiones casi
// no escriben nada y "la contraseña no aparece" sería verdad por no haber nada que mirar. Se sube la seguridad, el
// SQL con sus parámetros y el código propio. No se sube org.springframework.web: en DEBUG/TRACE, Spring escribe
// el "rejected value" de una validación fallida, que incluye la contraseña si es ella la que falla (ver README).
// El logger de MockMvc se baja porque imprime los cuerpos de la prueba, no de la aplicación.
@SpringBootTest(properties = {
        "logging.level.org.springframework.security=TRACE",
        "logging.level.org.hibernate.SQL=DEBUG",
        "logging.level.org.hibernate.orm.jdbc.bind=TRACE",
        "logging.level.ar.edu.um.cronos=TRACE",
        "logging.level.org.springframework.test=INFO"})
@AutoConfigureMockMvc
@IntegrationTestSupport
@ExtendWith(OutputCaptureExtension.class)
class RegistrationApiTest {

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

    private ResultActions register(String json) throws Exception {
        return mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String login, String password, String extra) {
        return "{\"login\":\"" + login + "\",\"password\":\"" + password + "\"" + extra + "}";
    }

    @Test
    void registraAnonimamenteYDevuelve201ConElUsuarioActivo() throws Exception {
        register("""
                {"login":"Juan.Perez","password":"%s","firstName":"Juan","lastName":"Pérez",
                 "email":"Juan@Ejemplo.test","imageUrl":"https://ejemplo.test/j.png","langKey":"en"}
                """.formatted(SECRETA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.login").value("juan.perez"))
                .andExpect(jsonPath("$.email").value("juan@ejemplo.test"))
                .andExpect(jsonPath("$.firstName").value("Juan"))
                .andExpect(jsonPath("$.langKey").value("en"))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.authorities.length()").value(1));
    }

    @Test
    void laRespuestaNuncaIncluyeLaContrasenaNiSuHash() throws Exception {
        String respuesta = register(body("juan", SECRETA, "")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        assertThat(respuesta).doesNotContain(SECRETA).doesNotContain("password").doesNotContain("$2");
    }

    @Test
    void laContrasenaSeGuardaConHashBCryptYNoEnClaro() throws Exception {
        register(body("juan", SECRETA, "")).andExpect(status().isCreated());

        String guardado = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE login = 'juan'", String.class);
        assertThat(guardado).startsWith("$2").hasSize(60).doesNotContain(SECRETA);
    }

    @Test
    void elClienteNoPuedeElegirIdActivacionNiAutoridades() throws Exception {
        register(body("juan", SECRETA, ",\"id\":999,\"activated\":false,\"authorities\":[\"ROLE_ADMIN\"],"
                + "\"createdDate\":\"2000-01-01T00:00:00Z\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(org.hamcrest.Matchers.not(999)))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.authorities.length()").value(1));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_authority WHERE authority = 'ROLE_ADMIN'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT created_at > now() - interval '1 minute' FROM app_user",
                Boolean.class)).isTrue();
    }

    @Test
    void sinIdiomaUsaEs() throws Exception {
        register(body("juan", SECRETA, "")).andExpect(jsonPath("$.langKey").value("es"));
    }

    @Test
    void unLoginRepetidoDa400UsernameAlreadyExistsAunqueCambienLasMayusculas() throws Exception {
        register(body("juan", SECRETA, "")).andExpect(status().isCreated());

        register(body("JUAN", SECRETA, ""))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("USERNAME_ALREADY_EXISTS"))
                .andExpect(jsonPath("$.path").value("/api/register"));
    }

    @Test
    void elRegistroYSusRechazosDejanSuEventoSinDatosPersonales() throws Exception {
        try (var log = new ar.edu.um.cronos.LogCapture()) {
            register(body("juan", SECRETA, ",\"email\":\"mismo@ejemplo.test\"")).andExpect(status().isCreated());
            register(body("JUAN", SECRETA, "")).andExpect(status().isBadRequest());
            register(body("ana", SECRETA, ",\"email\":\"mismo@ejemplo.test\"")).andExpect(status().isBadRequest());

            long id = jdbc.queryForObject("SELECT id FROM app_user WHERE login = 'juan'", Long.class);
            assertThat(log.eventos("usuario.registrado")).singleElement().satisfies(e ->
                    assertThat(e.getFormattedMessage()).isEqualTo("evento=usuario.registrado usuario=" + id));
            assertThat(log.eventos("usuario.registro.rechazado")).extracting(e -> e.getFormattedMessage())
                    .containsExactly("evento=usuario.registro.rechazado motivo=login_en_uso",
                            "evento=usuario.registro.rechazado motivo=email_en_uso");
            assertThat(log.list().stream().map(e -> e.getFormattedMessage()).filter(m -> m.startsWith("evento=")))
                    .noneMatch(m -> m.contains("juan") || m.contains("ana") || m.contains("mismo@"));
        }
    }

    @Test
    void unEmailRepetidoDa400EmailAlreadyExists() throws Exception {
        register(body("juan", SECRETA, ",\"email\":\"mismo@ejemplo.test\"")).andExpect(status().isCreated());

        register(body("ana", SECRETA, ",\"email\":\"MISMO@ejemplo.test\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_EXISTS"));
    }

    @Test
    void variosUsuariosSinEmailSePuedenRegistrar() throws Exception {
        register(body("juan", SECRETA, "")).andExpect(status().isCreated());
        register(body("ana", SECRETA, "")).andExpect(status().isCreated());
    }

    @Test
    void unaContrasenaFueraDeLargoDa400InvalidPassword() throws Exception {
        register(body("juan", "abc", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
        register(body("juan", "a".repeat(101), "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSWORD"));
        register(body("juan", "abcd", "")).andExpect(status().isCreated());
    }

    @Test
    void laContrasenaMasLargaDelContratoSeRegistraYSeGuarda() throws Exception {
        register(body("juan", "a".repeat(100), "")).andExpect(status().isCreated());
    }

    @Test
    void faltarLaContrasenaOElLoginDa400ValidationError() throws Exception {
        register("{\"login\":\"juan\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("password"));
        register("{\"password\":\"abcd\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("login"));
    }

    @Test
    void validaLoginEmailYLargosDeLosDemasCampos() throws Exception {
        String larga = "x".repeat(51);
        for (String extra : new String[] {
                "", ",\"email\":\"no-es-un-email\"", ",\"email\":\"a@b\"", ",\"firstName\":\"" + larga + "\"",
                ",\"lastName\":\"" + larga + "\"", ",\"imageUrl\":\"" + "u".repeat(257) + "\"",
                ",\"langKey\":\"e\"", ",\"langKey\":\"" + "e".repeat(11) + "\""}) {
            String login = extra.isEmpty() ? "ab" : "juan";
            register(body(login, SECRETA, extra)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        for (String login : new String[] {"juan perez", "juan/perez", "juan;drop", "a".repeat(51), "juán"}) {
            register(body(login, SECRETA, "")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    @Test
    void elErrorNoRepiteLosValoresRecibidosNiLaContrasena() throws Exception {
        String respuesta = register(body("juan perez", "abc", ",\"email\":\"no-es-un-email\""))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();

        assertThat(respuesta).doesNotContain("juan perez").doesNotContain("abc\"").doesNotContain("no-es-un-email");
    }

    @Test
    void unJsonRoto400SinEcoDelCuerpo() throws Exception {
        String respuesta = register("{\"login\": \"juan\", \"password\": " + SECRETA)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andReturn().getResponse()
                .getContentAsString();

        assertThat(respuesta).doesNotContain(SECRETA);
    }

    @Test
    void laContrasenaNuncaApareceEnLosLogs(CapturedOutput output) throws Exception {
        register(body("juan", SECRETA, "")).andExpect(status().isCreated());
        register(body("juan", SECRETA, "")).andExpect(status().isBadRequest());
        register(body("ana", "abc" + SECRETA.repeat(10), "")).andExpect(status().isBadRequest());

        assertThat(output.getAll()).isNotEmpty().doesNotContain(SECRETA);
    }

    @Test
    void soloElPostEstaAbierto() throws Exception {
        // Sin token, una ruta no habilitada responde 401 (no autenticado), no 403.
        mvc.perform(get("/api/register")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
