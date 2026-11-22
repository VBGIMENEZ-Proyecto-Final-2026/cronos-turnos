package ar.edu.um.cronos.adapters.in.web;

import ar.edu.um.cronos.IntegrationTestSupport;
import ar.edu.um.cronos.LogCapture;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** El identificador de pedido: se genera o se reutiliza, llega al encabezado y a cada línea del log, y se limpia. */
@SpringBootTest
@AutoConfigureMockMvc
@IntegrationTestSupport
class RequestLogFilterTest {

    @Autowired
    MockMvc mvc;

    @Test
    void sinEncabezadoSeGeneraUnIdentificadorYSeDevuelve() throws Exception {
        try (LogCapture log = new LogCapture()) {
            MvcResult result = mvc.perform(get("/api/account")).andReturn();

            String requestId = result.getResponse().getHeader("X-Request-Id");
            assertThat(requestId).matches("[0-9a-f]{12}");
            ILoggingEvent line = unaLinea(log.eventos("http.pedido.resuelto"));
            assertThat(line.getFormattedMessage())
                    .startsWith("evento=http.pedido.resuelto metodo=GET ruta=/api/account estado=401 ms=");
            assertThat(line.getMDCPropertyMap()).containsEntry("requestId", requestId);
        }
    }

    @Test
    void unIdentificadorSeguroDelClienteSeReutiliza() throws Exception {
        try (LogCapture log = new LogCapture()) {
            MvcResult result = mvc.perform(get("/api/account").header("X-Request-Id", "app-2026.11_22-0001"))
                    .andReturn();

            assertThat(result.getResponse().getHeader("X-Request-Id")).isEqualTo("app-2026.11_22-0001");
            assertThat(unaLinea(log.eventos("http.pedido.resuelto")).getMDCPropertyMap())
                    .containsEntry("requestId", "app-2026.11_22-0001");
        }
    }

    @Test
    void unIdentificadorQueNoEsSeguroSeDescartaYNoLlegaAlLog() throws Exception {
        for (String hostil : List.of("corto", "con espacios en el medio", "x".repeat(65),
                "valido-pero\nevento=usuario.login.ok usuario=1", "<script>alert(1)</script>")) {
            try (LogCapture log = new LogCapture()) {
                MvcResult result = mvc.perform(get("/api/account").header("X-Request-Id", hostil)).andReturn();

                String requestId = result.getResponse().getHeader("X-Request-Id");
                assertThat(requestId).as("para %s", hostil).matches("[0-9a-f]{12}");
                assertThat(log.text()).as("para %s", hostil).doesNotContain("usuario.login.ok").doesNotContain("<script>");
            }
        }
    }

    @Test
    void elIdentificadorSeLimpiaAlTerminarElPedido() throws Exception {
        mvc.perform(get("/api/account"));

        assertThat(MDC.get("requestId")).isNull();
    }

    @Test
    void elChequeoDeSaludNoEscribeUnaLineaPorPedido() throws Exception {
        try (LogCapture log = new LogCapture()) {
            mvc.perform(get("/actuator/health"));

            assertThat(log.eventos("http.pedido.resuelto")).isEmpty();
        }
    }

    private static ILoggingEvent unaLinea(List<ILoggingEvent> lines) {
        assertThat(lines).hasSize(1);
        return lines.get(0);
    }
}
