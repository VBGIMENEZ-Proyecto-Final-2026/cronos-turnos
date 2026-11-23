package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.TestJwtKeyInitializer;
import ar.edu.um.cronos.TestKeys;
import ar.edu.um.cronos.adapters.out.security.NimbusTokenIssuer;
import ar.edu.um.cronos.domain.User;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.core.annotation.Order;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La disponibilidad de punta a punta: la aplicación completa con PostgreSQL real y dos servidores HTTP simulados en
 * lugar de atlas y de la cátedra. Con tokens reales emitidos por el login.
 */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
// Un @ContextConfiguration directo reemplaza al de @IntegrationTestSupport: hay que nombrar los dos inicializadores.
@ContextConfiguration(initializers = {TestJwtKeyInitializer.class, AvailabilityApiTest.Servidores.class})
class AvailabilityApiTest {

    private static final String CLAVE = "clave-de-prueba-123";
    private static final String TOKEN_TECNICO = "token-tecnico-de-prueba";

    static final StubServer ATLAS = new StubServer();
    static final StubServer CATEDRA = new StubServer();

    /** Un lunes lejano en el futuro: ningún turno "ya empezó" aunque se corra a cualquier hora. */
    private static final LocalDate LUNES = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY)).plusWeeks(2);
    private static final LocalDate LUNES_PASADO = LocalDate.of(2020, 1, 6);

    private static final String PROFESIONAL = """
            {"id": 207, "firstName": "Diego", "lastName": "Alvarez", "enabled": true,
             "category": {"id": 103, "name": "Clinica medica", "enabled": true},
             "schedules": [
               {"id": 311, "dayOfWeek": "MONDAY", "startTime": "09:00:00", "endTime": "11:00:00",
                "slotDurationMinutes": 30, "enabled": true}]}""";

    /** Apunta atlas y la cátedra a los servidores de mentira. Corre después del inicializador base y le gana. */
    @Order(100)
    static class Servidores implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(
                    "cronos.atlas.base-url=" + ATLAS.url(),
                    "catedra.rest.base-url=" + CATEDRA.url(),
                    "cronos.atlas.max-retries=1",
                    "catedra.rest.max-retries=1",
                    "catedra.rest.id-token=" + TOKEN_TECNICO).applyTo(context);
        }
    }

    @AfterAll
    static void apagar() {
        ATLAS.close();
        CATEDRA.close();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void limpiar() {
        ATLAS.reset();
        CATEDRA.reset();
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");
    }

    // ---- ayudas ---------------------------------------------------------------------------------------------

    private String tokenDe(String login) throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}"))
                .andExpect(status().isCreated());
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + login + "\",\"password\":\"" + CLAVE + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions disponibilidad(String token, long profesional, String desde, String hasta) throws Exception {
        var pedido = get("/api/professionals/" + profesional + "/availability");
        if (desde != null) {
            pedido.param("from", desde);
        }
        if (hasta != null) {
            pedido.param("to", hasta);
        }
        if (token != null) {
            pedido.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(pedido);
    }

    private ResultActions disponibilidad(String token, LocalDate dia) throws Exception {
        return disponibilidad(token, 207, dia.toString(), dia.toString());
    }

    private static void atlasResponde(Reply... respuestas) {
        ATLAS.reply(respuestas);
    }

    private static String ocupaciones(String... items) {
        return "{\"professionalId\":207,\"occupancies\":[" + String.join(",", items) + "]}";
    }

    private static String ocupacion(LocalDate dia, String hora, String estado) {
        return "{\"date\":\"" + dia + "\",\"startTime\":\"" + hora + "\",\"status\":\"" + estado
                + "\",\"expiresAt\":null}";
    }

    private static String tokenSinRol(String rol) {
        Instant ahora = Instant.now();
        var usuario = new User(1L, "otro", "hash", null, null, null, null, "es", true, Set.of(rol), ahora, ahora);
        return new NimbusTokenIssuer((RSAPrivateKey) TestKeys.privateKey(), (RSAPublicKey) TestKeys.publicKey(),
                Duration.ofHours(1), Duration.ofDays(30), Clock.systemUTC()).issue(usuario, false);
    }

    // ---- el camino feliz -------------------------------------------------------------------------------------

    @Test
    void devuelveLosTurnosLibresDeLaGrillaMenosLasOcupaciones() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.json(200, ocupaciones(ocupacion(LUNES, "09:30:00", "CONFIRMED"),
                ocupacion(LUNES, "10:00:00", "HELD"))));

        disponibilidad(token, LUNES).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.professionalId").value(207))
                .andExpect(jsonPath("$.from").value(LUNES.toString()))
                .andExpect(jsonPath("$.to").value(LUNES.toString()))
                .andExpect(jsonPath("$.slots.length()").value(2))
                .andExpect(jsonPath("$.slots[0].date").value(LUNES.toString()))
                .andExpect(jsonPath("$.slots[0].startTime").value("09:00:00"))
                .andExpect(jsonPath("$.slots[0].endTime").value("09:30:00"))
                .andExpect(jsonPath("$.slots[1].startTime").value("10:30:00"))
                .andExpect(jsonPath("$.slots[1].endTime").value("11:00:00"));
    }

    @Test
    void aAtlasLeReenviaElTokenDelUsuarioYALaCatedraLeHablaConElTokenTecnico() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.json(200, ocupaciones()));

        disponibilidad(token, LUNES).andExpect(status().isOk());

        assertThat(ATLAS.requests()).hasSize(1);
        assertThat(ATLAS.requests().get(0).uri()).isEqualTo("/api/professionals/207");
        assertThat(ATLAS.requests().get(0).header("Authorization")).isEqualTo("Bearer " + token);
        assertThat(CATEDRA.requests()).hasSize(1);
        assertThat(CATEDRA.requests().get(0).uri()).startsWith("/api/appointment-occupancies?")
                .contains("professionalId=207", "from=" + LUNES, "to=" + LUNES);
        assertThat(CATEDRA.requests().get(0).header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
    }

    @Test
    void laRespuestaNoTrasladaDatosDelCatalogoMasQueLosTurnos() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.json(200, ocupaciones()));

        String cuerpo = disponibilidad(token, LUNES).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();

        assertThat(cuerpo).doesNotContain("Diego", "Alvarez", "Clinica");
    }

    @Test
    void unAdministradorTambienPuedeConsultar() throws Exception {
        atlasResponde(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.json(200, ocupaciones()));

        disponibilidad(tokenSinRol("ROLE_ADMIN"), LUNES).andExpect(status().isOk());
    }

    @Test
    void unRangoDeVariosDiasTraeTodosLosTurnosConUnaSolaConsultaALaCatedra() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.json(200, ocupaciones()));

        disponibilidad(token, 207, LUNES.toString(), LUNES.plusDays(14).toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(12)); // 3 lunes x 4 turnos

        assertThat(CATEDRA.requests()).hasSize(1);
    }

    // ---- lo que no se ofrece ---------------------------------------------------------------------------------

    @Test
    void unProfesionalDeshabilitadoNoOfreceTurnosNiSeConsultaALaCatedra() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.json(200, "{\"id\": 207, \"enabled\": false, \"schedules\": [{\"dayOfWeek\": \"MONDAY\","
                + " \"startTime\": \"09:00:00\", \"endTime\": \"11:00:00\", \"slotDurationMinutes\": 30,"
                + " \"enabled\": true}]}"));

        disponibilidad(token, LUNES).andExpect(status().isOk()).andExpect(jsonPath("$.slots.length()").value(0));

        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void unDiaPasadoNoTieneTurnos() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.json(200, ocupaciones()));

        disponibilidad(token, LUNES_PASADO).andExpect(status().isOk())
                .andExpect(jsonPath("$.slots.length()").value(0));
    }

    // ---- autenticación y autorización ------------------------------------------------------------------------

    @Test
    void sinTokenDa401YNoSeLlamaANadie() throws Exception {
        disponibilidad(null, LUNES).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));

        assertThat(ATLAS.requests()).isEmpty();
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void unTokenAlteradoDa401YNoSeLlamaANadie() throws Exception {
        String token = tokenDe("ana.demo");
        String alterado = token.substring(0, token.length() - 4) + "AAAA";

        disponibilidad(alterado, LUNES).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"));

        assertThat(ATLAS.requests()).isEmpty();
    }

    @Test
    void unTokenValidoSinRolDa403YNoSeLlamaANadie() throws Exception {
        disponibilidad(tokenSinRol("ROLE_INVITADO"), LUNES).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"));

        assertThat(ATLAS.requests()).isEmpty();
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void soloSePermiteGet() throws Exception {
        String token = tokenDe("ana.demo");

        mvc.perform(post("/api/professionals/207/availability").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // ---- errores de atlas y de la cátedra -------------------------------------------------------------------

    @Test
    void unProfesionalInexistenteDa404YNoSeConsultaALaCatedra() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.problem(404, "PROFESSIONAL_NOT_FOUND"));

        disponibilidad(token, 999, LUNES.toString(), LUNES.toString()).andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PROFESSIONAL_NOT_FOUND"))
                .andExpect(jsonPath("$.path").value("/api/professionals/999/availability"));

        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void siAtlasEstaCaidoDa503ConSuCodigoYNoSeConsultaALaCatedra() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.problem(503, "DOWN"));

        disponibilidad(token, LUNES).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));

        assertThat(ATLAS.requests()).as("un intento y un reintento, no más").hasSize(2);
        assertThat(CATEDRA.requests()).isEmpty();
    }

    @Test
    void unErrorInternoDeAtlasTambienDa503DelCatalogo() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.problem(500, "BOOM"));

        disponibilidad(token, LUNES).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));

        assertThat(ATLAS.requests()).hasSize(1);
    }

    @Test
    void siLaCatedraEstaCaidaDa503ConSuCodigoYNoSeInventanTurnos() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.problem(503, "DOWN"));

        disponibilidad(token, LUNES).andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE"))
                .andExpect(jsonPath("$.slots").doesNotExist());

        assertThat(CATEDRA.requests()).as("un intento y un reintento, no más").hasSize(2);
    }

    @Test
    void siLaCatedraRechazaLaCredencialTecnicaElUsuarioVeUn503GenericoSinElToken() throws Exception {
        String token = tokenDe("ana.demo");
        atlasResponde(Reply.json(200, PROFESIONAL));
        CATEDRA.reply(Reply.problem(401, "X"));

        String cuerpo = disponibilidad(token, LUNES).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CATEDRA_UNAVAILABLE")).andReturn().getResponse()
                .getContentAsString();

        assertThat(cuerpo).doesNotContain(TOKEN_TECNICO);
    }

    // ---- validación de la consulta --------------------------------------------------------------------------

    @Test
    void unRangoInvertidoDa400ConSuCodigoYNoSeLlamaANadie() throws Exception {
        String token = tokenDe("ana.demo");

        disponibilidad(token, 207, LUNES.plusDays(1).toString(), LUNES.toString()).andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

        assertThat(ATLAS.requests()).isEmpty();
    }

    @Test
    void unRangoDemasiadoLargoDa400() throws Exception {
        String token = tokenDe("ana.demo");

        disponibilidad(token, 207, LUNES.toString(), LUNES.plusDays(31).toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DATE_RANGE"));

        assertThat(ATLAS.requests()).isEmpty();
    }

    @Test
    void unaFechaConFormatoInvalidoDa400SinRepetirLoEnviado() throws Exception {
        String token = tokenDe("ana.demo");

        String cuerpo = disponibilidad(token, 207, "23/11/2026-secreto", LUNES.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"))
                .andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).doesNotContain("secreto");
        assertThat(ATLAS.requests()).isEmpty();
    }

    @Test
    void siFaltaUnaFechaDa400() throws Exception {
        String token = tokenDe("ana.demo");

        disponibilidad(token, 207, LUNES.toString(), null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("to"));
        disponibilidad(token, 207, null, LUNES.toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("from"));
    }

    @Test
    void unIdDeProfesionalQueNoEsUnNumeroDa400() throws Exception {
        String token = tokenDe("ana.demo");

        mvc.perform(get("/api/professionals/abc/availability").param("from", LUNES.toString())
                        .param("to", LUNES.toString()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---- los secretos no se filtran -------------------------------------------------------------------------

    @Test
    void niElTokenDelUsuarioNiElTecnicoApareceEnLosLogsAunConTodoCaido() throws Exception {
        String token = tokenDe("ana.demo");
        try (var log = new LogCapture()) {
            atlasResponde(Reply.problem(503, "DOWN"));
            disponibilidad(token, LUNES).andExpect(status().isServiceUnavailable());

            ATLAS.reset();
            CATEDRA.reset();
            atlasResponde(Reply.json(200, PROFESIONAL));
            CATEDRA.reply(Reply.problem(401, "X"));
            disponibilidad(token, LUNES).andExpect(status().isServiceUnavailable());

            assertThat(log.events()).as("tiene que haber algo que mirar").isPositive();
            assertThat(log.text())
                    .contains("evento=catalogo.no_disponible metodo=GET ruta=/api/professionals/207/availability estado=503")
                    .contains("evento=catedra.no_disponible metodo=GET ruta=/api/professionals/207/availability estado=503")
                    .contains("evento=catedra.credencial.rechazada operacion=consultar_ocupaciones")
                    .doesNotContain(token).doesNotContain(TOKEN_TECNICO);
        }
    }
}
