package ar.edu.um.cronos.logging;

import ar.edu.um.cronos.LogCapture;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** La forma de cada línea: código estable, pares clave=valor, una sola línea y valores acotados. */
class LogEventTest {

    private static final Logger LOG = LoggerFactory.getLogger(LogEventTest.class);

    @Test
    void laLineaEmpiezaConElCodigoDelEventoSeguidoDeLosPares() {
        assertThat(LogEvent.format("reserva.hold.creado", "proceso", "process-ab12", "usuario", 7, "ms", 212L))
                .isEqualTo("evento=reserva.hold.creado proceso=process-ab12 usuario=7 ms=212");
    }

    @Test
    void unEventoSinCamposEsSoloElCodigo() {
        assertThat(LogEvent.format("arranque.listo")).isEqualTo("evento=arranque.listo");
    }

    @Test
    void nuloSeEscribeConGuion() {
        assertThat(LogEvent.format("x.y", "codigo", null)).isEqualTo("evento=x.y codigo=-");
    }

    @Test
    void unCampoSinValorSeEscribeConGuionEnLugarDeRomper() {
        assertThat(LogEvent.format("x.y", "a", 1, "huerfano")).isEqualTo("evento=x.y a=1 huerfano=-");
    }

    @Test
    void unEnumSeEscribeConSuNombre() {
        assertThat(LogEvent.format("x.y", "estado", Level.WARN)).isEqualTo("evento=x.y estado=WARN");
    }

    @Test
    void unValorConEspaciosIgualesOComillasVaEntreComillas() {
        assertThat(LogEvent.format("x.y", "a", "dos palabras", "b", "k=v", "c", "dijo \"hola\"", "d", ""))
                .isEqualTo("evento=x.y a=\"dos palabras\" b=\"k=v\" c=\"dijo \\\"hola\\\"\" d=\"\"");
    }

    @Test
    void unSaltoDeLineaEnUnValorNoFabricaUnaLineaNueva() {
        // Un atacante manda una ruta con un salto de línea para simular un evento legítimo en el log.
        String hostil = "/api/x\nevento=usuario.login.ok usuario=1";

        String linea = LogEvent.format("http.pedido.resuelto", "ruta", hostil);

        assertThat(linea).doesNotContain("\n").doesNotContain("\r");
        assertThat(linea).isEqualTo("evento=http.pedido.resuelto ruta=\"/api/x\\nevento=usuario.login.ok usuario=1\"");
    }

    @Test
    void losCaracteresDeControlSeEscapan() {
        String linea = LogEvent.format("x.y", "a", "uno\rdos\ttres\u0007cuatro");

        assertThat(linea).doesNotContain("\r").doesNotContain("\t").doesNotContain("\u0007");
        assertThat(linea).contains("uno\\rdos\\ttres\\u0007cuatro");
    }

    @Test
    void unValorLargoSeCorta() {
        String linea = LogEvent.format("x.y", "a", "z".repeat(LogEvent.MAX_VALUE + 50));

        assertThat(linea).isEqualTo("evento=x.y a=" + "z".repeat(LogEvent.MAX_VALUE) + "…");
    }

    @Test
    void unaClaveConCaracteresRarosNoRompeElFormato() {
        assertThat(LogEvent.format("x.y", "Clave con=espacio", 1)).isEqualTo("evento=x.y _lave_con_espacio=1");
    }

    @Test
    void infoYWarnEscribenUnaSolaLineaAEsteNivel() {
        try (LogCapture log = new LogCapture()) {
            LogEvent.info(LOG, "prueba.hito", "k", 1);
            LogEvent.warn(LOG, "prueba.degradacion", "k", 2);

            List<ILoggingEvent> events = log.list();
            assertThat(events).hasSize(2);
            assertThat(events.get(0).getLevel()).isEqualTo(Level.INFO);
            assertThat(events.get(0).getFormattedMessage()).isEqualTo("evento=prueba.hito k=1");
            assertThat(events.get(1).getLevel()).isEqualTo(Level.WARN);
            assertThat(events.get(1).getFormattedMessage()).isEqualTo("evento=prueba.degradacion k=2");
            assertThat(events).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
        }
    }

    @Test
    void soloErrorLlevaLaTraza() {
        try (LogCapture log = new LogCapture()) {
            LogEvent.error(LOG, "prueba.bug", new IllegalStateException("falló"), "k", 3);

            ILoggingEvent event = log.list().get(0);
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("evento=prueba.bug k=3");
            assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
        }
    }

    @Test
    void conElNivelApagadoNoSeEscribeNada() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger("prueba.apagado");
        try (LogCapture log = new LogCapture()) {
            logger.setLevel(Level.ERROR);
            LogEvent.info(logger, "prueba.hito", "k", 1);
            LogEvent.warn(logger, "prueba.degradacion", "k", 2);

            assertThat(log.events()).isZero();
        } finally {
            logger.setLevel(null);
        }
    }
}
