package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CORS: sin orígenes configurados no se acepta ninguno; con una lista solo entran esos, sin credenciales ni comodines. */
class CorsApiTest {

    private static final String PERMITIDO = "https://app.ejemplo.test";
    private static final String OTRO = "https://otro.ejemplo.test";

    private static ResultActions consultaPrevia(MockMvc mvc, String origen, String metodo, String ruta) throws Exception {
        return mvc.perform(options(ruta).header(HttpHeaders.ORIGIN, origen)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, metodo)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"));
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @IntegrationTestSupport
    class SinOrigenesConfigurados {

        @Autowired
        MockMvc mvc;

        @Test
        void ningunOrigenPasaLaConsultaPrevia() throws Exception {
            // Sin configuración no hay encabezados de permiso: el navegador bloquea la consulta real.
            consultaPrevia(mvc, PERMITIDO, "POST", "/api/authenticate")
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
        }

        @Test
        void unaConsultaConOrigenNoRecibeElEncabezadoDePermiso() throws Exception {
            mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, PERMITIDO))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Nested
    @SpringBootTest(properties = "cronos.security.cors.allowed-origins=" + PERMITIDO)
    @AutoConfigureMockMvc
    @IntegrationTestSupport
    class ConUnOrigenConfigurado {

        @Autowired
        MockMvc mvc;

        @Test
        void elOrigenListadoPasaLaConsultaPreviaDelLoginSinCredenciales() throws Exception {
            consultaPrevia(mvc, PERMITIDO, "POST", "/api/authenticate").andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PERMITIDO))
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,OPTIONS"))
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        }

        @Test
        void tambienPasaLaConsultaPreviaDeLaCuenta() throws Exception {
            consultaPrevia(mvc, PERMITIDO, "GET", "/api/account").andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PERMITIDO));
        }

        @Test
        void otroOrigenNoPasa() throws Exception {
            consultaPrevia(mvc, OTRO, "POST", "/api/authenticate").andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        void unMetodoQueLaApiNoUsaNoPasa() throws Exception {
            consultaPrevia(mvc, PERMITIDO, HttpMethod.DELETE.name(), "/api/account").andExpect(status().isForbidden());
            consultaPrevia(mvc, PERMITIDO, HttpMethod.PUT.name(), "/api/account").andExpect(status().isForbidden());
        }

        @Test
        void unEncabezadoQueLaApiNoUsaNoPasa() throws Exception {
            mvc.perform(options("/api/account").header(HttpHeaders.ORIGIN, PERMITIDO)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-secreto"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void laConsultaRealDelOrigenListadoRecibeElPermiso() throws Exception {
            mvc.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, PERMITIDO))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, PERMITIDO));
        }
    }
}
