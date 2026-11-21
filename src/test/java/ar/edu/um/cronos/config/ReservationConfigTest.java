package ar.edu.um.cronos.config;

import ar.edu.um.cronos.StubServer;
import ar.edu.um.cronos.StubServer.Reply;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.HoldGateway;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** El cliente de holds tal como lo arma la configuración, contra HTTP real. */
class ReservationConfigTest {

    private static final String TOKEN_TECNICO = "jwt-tecnico-de-la-catedra";
    private static final LocalDate DIA = LocalDate.of(2026, 11, 24);
    private static final LocalTime HORA = LocalTime.of(14, 30);
    private static final String HOLD = "{\"holdId\":\"h\",\"reservationProcessId\":\"p\",\"expiresAt\":\"2026-11-24T14:44:50Z\"}";

    private final ReservationConfig config = new ReservationConfig();

    private HoldGateway gateway(String url, String readTimeout) {
        return config.holdGateway(new CatedraProperties(url, TOKEN_TECNICO, Duration.ofSeconds(2),
                Duration.parse(readTimeout), 3));
    }

    @Test
    void laCatedraRecibeElTokenTecnicoDelProyecto() {
        try (var catedra = new StubServer().reply(Reply.json(201, HOLD))) {
            gateway(catedra.url(), "PT2S").createHold(218, DIA, HORA, "user-7");

            assertThat(catedra.requests().get(0).header("Authorization")).isEqualTo("Bearer " + TOKEN_TECNICO);
        }
    }

    @Test
    void aunConReintentosConfiguradosParaLasLecturasLosHoldsNuncaSeReintentan() {
        try (var catedra = new StubServer().reply(Reply.problem(503, "DOWN"))) {
            var gateway = gateway(catedra.url(), "PT2S");

            assertThatThrownBy(() -> gateway.createHold(218, DIA, HORA, "user-7"))
                    .isInstanceOf(CatedraUnavailableException.class);
            assertThatThrownBy(() -> gateway.confirmHold("h", "user-7", "Juan", "Pérez"))
                    .isInstanceOf(CatedraUnavailableException.class);

            assertThat(catedra.requests()).as("un pedido por llamada aunque catedra.rest.max-retries sea 3").hasSize(2);
        }
    }

    @Test
    void unaRespuestaLentaCortaPorTimeoutYNoSeSabeQuePaso() {
        try (var catedra = new StubServer().reply(Reply.json(201, HOLD).delayed(1500))) {
            long inicio = System.nanoTime();

            assertThatThrownBy(() -> gateway(catedra.url(), "PT0.3S").createHold(218, DIA, HORA, "user-7"))
                    .isInstanceOf(CatedraUnavailableException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(Duration.ofMillis(1200));
            assertThat(catedra.requests()).hasSize(1);
        }
    }

    @Test
    void unaRedireccionNoSeSigueYElTokenNoSeReenvia() {
        try (var destino = new StubServer().reply(Reply.json(201, HOLD));
             var catedra = new StubServer().reply(Reply.redirect(destino.url() + "/robado"))) {
            assertThatThrownBy(() -> gateway(catedra.url(), "PT2S").createHold(218, DIA, HORA, "user-7"))
                    .isInstanceOf(RuntimeException.class);

            assertThat(destino.requests()).as("el destino de la redirección nunca recibe nada").isEmpty();
        }
    }
}
