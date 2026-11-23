package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogLevels;
import ar.edu.um.cronos.TestKeys;
import ar.edu.um.cronos.adapters.out.security.NimbusTokenIssuer;
import ar.edu.um.cronos.domain.User;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSObject;
import com.nimbusds.jose.Payload;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
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
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La seguridad de las rutas protegidas de punta a punta, con tokens reales emitidos por el login y con tokens
 * armados a mano para los casos que un login honesto nunca produce (vencido, otra clave, sin vencimiento, otro
 * algoritmo). PostgreSQL real.
 */
@SpringBootTest(properties = {
        "logging.level.org.springframework.security=TRACE",
        "logging.level.ar.edu.um.cronos=TRACE",
        "logging.level.org.springframework.test=INFO"})
@AutoConfigureMockMvc
@IntegrationTestSupport
@ExtendWith(OutputCaptureExtension.class)
class AccountApiTest {

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

    // ---- ayudas -----------------------------------------------------------------------------------------------

    private void registrar(String login) throws Exception {
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"" + login + "\",\"password\":\"" + SECRETA + "\",\"email\":\""
                                + login + "@ejemplo.test\"}"))
                .andExpect(status().isCreated());
    }

    /** Registra, inicia sesión por la API real y devuelve el token. */
    private String tokenDe(String login) throws Exception {
        registrar(login);
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + login + "\",\"password\":\"" + SECRETA + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");
    }

    private ResultActions account(String token) throws Exception {
        return mvc.perform(get("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static User usuario(String login, Set<String> autoridades) {
        Instant ahora = Instant.now();
        return new User(1L, login, "hash", null, null, null, null, "es", true, autoridades, ahora, ahora);
    }

    /** Un token con la forma de los de cronos, firmado con la clave del par dado y con el reloj dado. */
    private static String emitir(KeyPair par, Clock reloj, User usuario) {
        return new NimbusTokenIssuer((java.security.interfaces.RSAPrivateKey) par.getPrivate(),
                (java.security.interfaces.RSAPublicKey) par.getPublic(), Duration.ofHours(1), Duration.ofDays(30),
                reloj).issue(usuario, false);
    }

    private static KeyPair parDeLaAplicacion() {
        return new KeyPair(TestKeys.publicKey(), TestKeys.privateKey());
    }

    private void exigir401(ResultActions result) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.code").value("NO_AUTENTICADO"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.path").value("/api/account"));
    }

    // ---- GET /api/account -------------------------------------------------------------------------------------

    @Test
    void conUnTokenValidoDevuelveLaCuentaDeQuienLoPide() throws Exception {
        String token = tokenDe("juan.perez");

        account(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value("juan.perez"))
                .andExpect(jsonPath("$.email").value("juan.perez@ejemplo.test"))
                .andExpect(jsonPath("$.activated").value(true))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void cadaUsuarioVeSoloLoSuyoYNoPuedePedirLaCuentaDeOtro() throws Exception {
        String deJuan = tokenDe("juan");
        String deAna = tokenDe("ana");

        account(deJuan).andExpect(jsonPath("$.login").value("juan"));
        account(deAna).andExpect(jsonPath("$.login").value("ana"));

        // Un identificador mandado por el cliente no cambia de quién es la cuenta: manda el token.
        mvc.perform(get("/api/account").param("login", "ana").param("username", "ana").param("id", "2")
                        .header("X-User", "ana").header(HttpHeaders.AUTHORIZATION, "Bearer " + deJuan))
                .andExpect(status().isOk()).andExpect(jsonPath("$.login").value("juan"));
    }

    @Test
    void noHayRutaParaPedirLaCuentaDeOtroPorLogin() throws Exception {
        String deJuan = tokenDe("juan");
        registrar("ana");

        mvc.perform(get("/api/account/ana").header(HttpHeaders.AUTHORIZATION, "Bearer " + deJuan))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        mvc.perform(get("/api/users/ana").header(HttpHeaders.AUTHORIZATION, "Bearer " + deJuan))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void unAdministradorTambienPuedeVerSuCuenta() throws Exception {
        registrar("jefa");
        jdbc.update("INSERT INTO user_authority (user_id, authority) SELECT id, 'ROLE_ADMIN' FROM app_user "
                + "WHERE login = 'jefa'");
        String body = mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jefa\",\"password\":\"" + SECRETA + "\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"id_token\":\"([^\"]+)\".*", "$1");

        account(token).andExpect(status().isOk())
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_ADMIN"))
                .andExpect(jsonPath("$.authorities[1]").value("ROLE_USER"));
    }

    // ---- 401: no autenticado ----------------------------------------------------------------------------------

    @Test
    void sinTokenDa401ConProblemJson() throws Exception {
        exigir401(mvc.perform(get("/api/account")));
    }

    @Test
    void conUnEsquemaQueNoEsBearerDa401() throws Exception {
        exigir401(mvc.perform(get("/api/account").header(HttpHeaders.AUTHORIZATION, "Basic YW5hOmNsYXZl")));
    }

    @Test
    void unTokenQueNoEsUnJwtDa401() throws Exception {
        exigir401(account("esto-no-es-un-token"));
    }

    @Test
    void unTokenAlteradoDa401() throws Exception {
        String token = tokenDe("juan");
        String[] partes = token.split("\\.");
        // Se cambia el contenido (el login) y se deja la firma original: la firma ya no corresponde.
        String payload = new String(Base64.getUrlDecoder().decode(partes[1]), StandardCharsets.UTF_8)
                .replace("\"sub\":\"juan\"", "\"sub\":\"ana\"");
        String alterado = partes[0] + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + partes[2];

        exigir401(account(alterado));
    }

    @Test
    void unTokenFirmadoConOtraClaveDa401() throws Exception {
        registrar("juan");
        String token = emitir(TestKeys.generate(2048), Clock.systemUTC(), usuario("juan", Set.of("ROLE_USER")));

        exigir401(account(token));
    }

    @Test
    void unTokenVencidoDa401() throws Exception {
        registrar("juan");
        Clock haceDosHoras = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String token = emitir(parDeLaAplicacion(), haceDosHoras, usuario("juan", Set.of("ROLE_USER")));

        exigir401(account(token));
    }

    @Test
    void unTokenRecienVencidoDentroDeLaToleranciaTodaviaAnda() throws Exception {
        registrar("juan");
        // Venció hace 30 segundos y la tolerancia de reloj por defecto es de 60.
        Clock emitido = Clock.fixed(Instant.now().minus(Duration.ofHours(1)).minusSeconds(30), ZoneOffset.UTC);
        String token = emitir(parDeLaAplicacion(), emitido, usuario("juan", Set.of("ROLE_USER")));

        account(token).andExpect(status().isOk());
    }

    @Test
    void unTokenSinVencimientoDa401() throws Exception {
        registrar("juan");
        RSAKey jwk = new RSAKey.Builder(TestKeys.publicKey()).privateKey(TestKeys.privateKey()).build();
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        JwtClaimsSet claims = JwtClaimsSet.builder().subject("juan").claim("auth", "ROLE_USER")
                .issuedAt(Instant.now()).build();
        String token = encoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims)).getTokenValue();

        exigir401(account(token));
    }

    @Test
    void unTokenSinAlgoritmoNoneDa401() throws Exception {
        registrar("juan");
        String exp = String.valueOf(Instant.now().plusSeconds(3600).getEpochSecond());
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String token = b64.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + b64.encodeToString(("{\"sub\":\"juan\",\"auth\":\"ROLE_ADMIN\",\"exp\":" + exp + "}")
                .getBytes(StandardCharsets.UTF_8)) + ".";

        exigir401(account(token));
    }

    /** El ataque clásico de confusión de algoritmo: firmar con HS256 usando la clave pública como secreto. */
    @Test
    void unTokenHs256FirmadoConLaClavePublicaComoSecretoDa401() throws Exception {
        registrar("juan");
        long exp = Instant.now().plusSeconds(3600).getEpochSecond();
        JWSObject jws = new JWSObject(new JWSHeader(JWSAlgorithm.HS256),
                new Payload("{\"sub\":\"juan\",\"auth\":\"ROLE_ADMIN\",\"exp\":" + exp + "}"));
        jws.sign(new MACSigner(TestKeys.publicKey().getEncoded()));

        exigir401(account(jws.serialize()));
    }

    @Test
    void unTokenValidoDeUnUsuarioQueYaNoExisteDa401() throws Exception {
        String token = tokenDe("juan");
        jdbc.update("DELETE FROM user_authority");
        jdbc.update("DELETE FROM app_user");

        exigir401(account(token));
    }

    @Test
    void unTokenValidoDeUnUsuarioInactivoDa401() throws Exception {
        String token = tokenDe("juan");
        jdbc.update("UPDATE app_user SET activated = false WHERE login = 'juan'");

        exigir401(account(token));
    }

    // ---- 403: autenticado pero sin permiso --------------------------------------------------------------------

    @Test
    void unTokenValidoSinUnRolQueAlcanceDa403() throws Exception {
        registrar("juan");
        String token = emitir(parDeLaAplicacion(), Clock.systemUTC(), usuario("juan", Set.of("ROLE_OTRO")));

        account(token).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("SIN_PERMISO"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.path").value("/api/account"));
    }

    @Test
    void unTokenValidoSinAutoridadesDa403() throws Exception {
        registrar("juan");
        String token = emitir(parDeLaAplicacion(), Clock.systemUTC(), usuario("juan", Set.of()));

        account(token).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    @Test
    void unaRutaNoHabilitadaDa403AunConUnTokenValido() throws Exception {
        String token = tokenDe("juan");

        mvc.perform(get("/api/inexistente").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIN_PERMISO"));
        mvc.perform(post("/api/account").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SIN_PERMISO"));
    }

    // ---- lo que no se filtra ----------------------------------------------------------------------------------

    @Test
    void losRechazosNoDicenPorQueFallaElTokenNiRepitenNada() throws Exception {
        registrar("juan");
        Clock haceDosHoras = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String vencido = emitir(parDeLaAplicacion(), haceDosHoras, usuario("juan", Set.of("ROLE_USER")));
        String otraClave = emitir(TestKeys.generate(2048), Clock.systemUTC(), usuario("juan", Set.of("ROLE_USER")));

        String delVencido = account(vencido).andReturn().getResponse().getContentAsString();
        String delOtraClave = account(otraClave).andReturn().getResponse().getContentAsString();

        assertThat(delVencido).isEqualTo(delOtraClave).doesNotContain(vencido).doesNotContain("juan")
                .doesNotContainIgnoringCase("expired").doesNotContainIgnoringCase("signature");
    }

    @Test
    void elTokenNuncaApareceEnLosLogs(CapturedOutput output) throws Exception {
        String bueno = tokenDe("juan");
        account(bueno).andExpect(status().isOk());
        String firma = bueno.split("\\.")[2];
        String malo = emitir(TestKeys.generate(2048), Clock.systemUTC(), usuario("juan", Set.of("ROLE_USER")));
        account(malo).andExpect(status().isUnauthorized());

        assertThat(output.getAll()).isNotEmpty().contains("evento=seguridad.acceso.no_autenticado metodo=GET ruta=/api/account")
                .doesNotContain(bueno).doesNotContain(malo).doesNotContain(firma).doesNotContain(SECRETA);
    }

    @Test
    void lasRutasPublicasSiguenAbiertasSinToken() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(post("/api/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"login\":\"nueva\",\"password\":\"" + SECRETA + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/authenticate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nueva\",\"password\":\"" + SECRETA + "\"}"))
                .andExpect(status().isOk());
    }

    /** Un cliente con un token viejo guardado debe poder iniciar sesión de nuevo: el login no lo necesita. */
    @Test
    void unTokenVencidoEnUnaRutaPublicaNoImpideIniciarSesion() throws Exception {
        registrar("juan");
        Clock haceDosHoras = Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC);
        String vencido = emitir(parDeLaAplicacion(), haceDosHoras, usuario("juan", Set.of("ROLE_USER")));

        mvc.perform(post("/api/authenticate").header(HttpHeaders.AUTHORIZATION, "Bearer " + vencido)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"juan\",\"password\":\"" + SECRETA + "\"}"))
                .andExpect(status().isOk());
    }
}
