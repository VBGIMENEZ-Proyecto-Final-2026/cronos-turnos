package ar.edu.um.cronos.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReservationTest {

    private static final Instant T0 = Instant.parse("2026-11-23T12:00:00Z");
    private static final Instant T1 = T0.plusSeconds(5);
    private static final Instant VENCE = Instant.parse("2026-11-23T12:10:00Z");
    private static final Slot TURNO = new Slot(LocalDate.of(2026, 11, 24), LocalTime.of(14, 30), LocalTime.of(15, 0));

    private static Reservation nueva() {
        return Reservation.start(7, 218, "Romina", "Acosta", TURNO, "Juan", "Pérez", T0);
    }

    @Test
    void unProcesoNuevoNaceEnStartingSinHoldNiVencimiento() {
        var r = nueva();

        assertThat(r.id()).isNull();
        assertThat(r.status()).isEqualTo(ReservationStatus.STARTING);
        assertThat(r.holdId()).isNull();
        assertThat(r.reservationProcessId()).isNull();
        assertThat(r.expiresAt()).isNull();
        assertThat(r.userId()).isEqualTo(7);
        assertThat(r.createdAt()).isEqualTo(T0).isEqualTo(r.updatedAt());
    }

    @Test
    void elCaminoFelizPasaPorHeldYWaitingForPhone() {
        var held = nueva().held("hold-1", "process-1", VENCE, T1);
        var esperando = held.waitingForPhone(VENCE.plusSeconds(60), T1.plusSeconds(1));

        assertThat(held.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(held.holdId()).isEqualTo("hold-1");
        assertThat(held.reservationProcessId()).isEqualTo("process-1");
        assertThat(esperando.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(esperando.expiresAt()).isEqualTo(VENCE.plusSeconds(60));
        assertThat(esperando.updatedAt()).isEqualTo(T1.plusSeconds(1));
        assertThat(esperando.createdAt()).isEqualTo(T0);
    }

    @Test
    void siLaConfirmacionNoTrajoVencimientoSeConservaElDelHold() {
        var esperando = nueva().held("hold-1", "process-1", VENCE, T1).waitingForPhone(null, T1);

        assertThat(esperando.expiresAt()).isEqualTo(VENCE);
    }

    @Test
    void esInmutable() {
        var original = nueva();
        original.held("hold-1", "process-1", VENCE, T1);

        assertThat(original.status()).isEqualTo(ReservationStatus.STARTING);
        assertThat(original.holdId()).isNull();
    }

    @Test
    void unaFallaGuardaSuMotivoEstable() {
        var fallida = nueva().failed("SLOT_ALREADY_HELD", T1);

        assertThat(fallida.status()).isEqualTo(ReservationStatus.FAILED);
        assertThat(fallida.failureCode()).isEqualTo("SLOT_ALREADY_HELD");
    }

    // ---- lo que informa la cátedra por Kafka -----------------------------------------------------------------

    private static Reservation esperando() {
        return nueva().held("hold-1", "process-1", VENCE, T1).waitingForPhone(VENCE, T1);
    }

    @Test
    void elPedidoDeTelefonoGuardaSuEventIdYNoCambiaElEstado() {
        var held = nueva().held("hold-1", "process-1", VENCE, T1);

        var pedido = held.informationRequested("request-event-1", T1.plusSeconds(1));

        assertThat(pedido.requestEventId()).isEqualTo("request-event-1");
        assertThat(pedido.status()).isEqualTo(ReservationStatus.HELD);
        assertThat(pedido.updatedAt()).isEqualTo(T1.plusSeconds(1));
        assertThat(held.requestEventId()).as("inmutable").isNull();
        // Un pedido más nuevo reemplaza al anterior y el proceso ya esperando también lo guarda.
        assertThat(esperando().informationRequested("request-event-1", T1).informationRequested("request-event-2", T1)
                .requestEventId()).isEqualTo("request-event-2");
    }

    @Test
    void unRechazoDelTelefonoGuardaElMotivoYElProcesoSigueEsperando() {
        var rechazado = esperando().informationRequested("request-event-1", T1)
                .informationRejected("INVALID_PHONE_NUMBER", T1.plusSeconds(2));

        assertThat(rechazado.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(rechazado.status().isFinal()).isFalse();
        assertThat(rechazado.lastRejectionReason()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(rechazado.requestEventId()).as("el pedido sigue siendo el mismo").isEqualTo("request-event-1");
        assertThat(rechazado.failureCode()).as("un rechazo no es una falla del proceso").isNull();
        // Y se puede confirmar después: el rechazo no cerró nada.
        assertThat(rechazado.confirmed(8251, T1.plusSeconds(3)).status()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void elMotivoDeUnRechazoSoloSeGuardaSiEsUnCodigoEstable() {
        for (String raro : new String[]{null, "", "texto libre con espacios", "minúscula", "A".repeat(61),
                "+5492615551234", "INVALID PHONE"}) {
            assertThat(esperando().informationRejected(raro, T1).lastRejectionReason())
                    .as("motivo %s", raro).isEqualTo("OTHER");
        }
        assertThat(esperando().informationRejected("A".repeat(60), T1).lastRejectionReason()).hasSize(60);
    }

    @Test
    void laConfirmacionGuardaElIdDeLaReservaDeLaCatedra() {
        var confirmada = esperando().confirmed(8251, T1.plusSeconds(1));

        assertThat(confirmada.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(confirmada.catedraReservationId()).isEqualTo(8251L);
        assertThat(confirmada.holdId()).isEqualTo("hold-1");
        assertThat(confirmada.expiresAt()).isEqualTo(VENCE);
    }

    @Test
    void unProcesoInvalidoGuardaElMotivoPeroSoloComoCodigoEstable() {
        var invalido = esperando().invalid("REQUEST_EVENT_MISMATCH", T1);

        assertThat(invalido.status()).isEqualTo(ReservationStatus.INVALID);
        assertThat(invalido.failureCode()).isEqualTo("REQUEST_EVENT_MISMATCH");
        assertThat(esperando().invalid("Un texto libre de la cátedra", T1).failureCode()).isEqualTo("OTHER");
        // Desde HELD también es posible (referencia 15.9).
        assertThat(nueva().held("hold-1", "process-1", VENCE, T1).invalid("X_Y", T1).status())
                .isEqualTo(ReservationStatus.INVALID);
    }

    @Test
    void soloUnaReservaConfirmadaSePuedeCancelar() {
        var cancelada = esperando().confirmed(8251, T1).cancelled(T1.plusSeconds(1));

        assertThat(cancelada.status()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(cancelada.catedraReservationId()).as("la reserva cancelada conserva su id").isEqualTo(8251L);
        assertThatThrownBy(() -> esperando().cancelled(T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> nueva().cancelled(T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unEventoSoloTieneSentidoSobreUnProcesoConHoldQueNoTermino() {
        var confirmada = esperando().confirmed(8251, T1);
        var expirada = esperando().expired(T1);

        for (var r : new Reservation[]{nueva(), confirmada, expirada}) {
            assertThatThrownBy(() -> r.informationRequested("e", T1)).as("requested en %s", r.status())
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> r.informationRejected("INVALID_PHONE_NUMBER", T1))
                    .as("rejected en %s", r.status()).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> esperando().informationRequested(null, T1)).isInstanceOf(NullPointerException.class);
    }

    // ---- el teléfono -------------------------------------------------------------------------------------------

    @Test
    void mandarElTelefonoGuardaSoloCuandoYNoCambiaNadaMas() {
        var esperando = esperando().informationRequested("request-event-1", T1);

        var enviado = esperando.phoneSubmitted(T1.plusSeconds(3));

        assertThat(enviado.phoneSubmittedAt()).isEqualTo(T1.plusSeconds(3));
        assertThat(enviado.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(enviado.requestEventId()).isEqualTo("request-event-1");
        assertThat(enviado.updatedAt()).isEqualTo(T1.plusSeconds(3));
        assertThat(esperando.phoneSubmittedAt()).as("inmutable").isNull();
    }

    @Test
    void soloSePuedeMandarElTelefonoMientrasSeEspera() {
        assertThatThrownBy(() -> nueva().phoneSubmitted(T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> nueva().held("hold-1", "process-1", VENCE, T1).phoneSubmitted(T1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> esperando().confirmed(8251, T1).phoneSubmitted(T1))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> esperando().expired(T1).phoneSubmitted(T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unRechazoDeLaCatedraPermiteMandarOtroTelefono() {
        var enviado = esperando().informationRequested("request-event-1", T1).phoneSubmitted(T1.plusSeconds(1));

        var rechazado = enviado.informationRejected("INVALID_PHONE_NUMBER", T1.plusSeconds(2));

        assertThat(rechazado.phoneSubmittedAt()).isNull();
        assertThat(rechazado.status()).isEqualTo(ReservationStatus.WAITING_FOR_PHONE);
        assertThat(rechazado.phoneSubmitted(T1.plusSeconds(3)).phoneSubmittedAt()).isEqualTo(T1.plusSeconds(3));
    }

    @Test
    void unPedidoNuevoDeLaCatedraTambienPermiteResponderDeNuevo() {
        var enviado = esperando().informationRequested("request-event-1", T1).phoneSubmitted(T1.plusSeconds(1));

        var otraVez = enviado.informationRequested("request-event-2", T1.plusSeconds(2));

        assertThat(otraVez.phoneSubmittedAt()).isNull();
        assertThat(otraVez.requestEventId()).isEqualTo("request-event-2");
    }

    @Test
    void unResultadoFinalNoBorraCuandoSeMandoElTelefono() {
        var enviado = esperando().informationRequested("request-event-1", T1).phoneSubmitted(T1.plusSeconds(1));

        assertThat(enviado.confirmed(8251, T1.plusSeconds(2)).phoneSubmittedAt()).isEqualTo(T1.plusSeconds(1));
        assertThat(enviado.expired(T1.plusSeconds(2)).phoneSubmittedAt()).isEqualTo(T1.plusSeconds(1));
    }

    @Test
    void unaTransicionNoPermitidaSeRechaza() {
        assertThatThrownBy(() -> nueva().waitingForPhone(VENCE, T1))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("STARTING -> WAITING_FOR_PHONE");
        assertThatThrownBy(() -> nueva().expired(T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unEstadoFinalNoRetrocede() {
        var expirada = nueva().held("hold-1", "process-1", VENCE, T1).expired(T1);

        assertThatThrownBy(() -> expirada.waitingForPhone(VENCE, T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> expirada.confirmed(8251, T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> expirada.invalid("X", T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> expirada.cancelled(T1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> expirada.failed("X", T1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elHoldSeExigeCompleto() {
        assertThatThrownBy(() -> nueva().held(null, "process-1", VENCE, T1)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> nueva().held("hold-1", null, VENCE, T1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void elPacienteDeLaCatedraEsOpacoYNoRevelaElLogin() {
        assertThat(Reservation.externalPatientId(42)).isEqualTo("user-42");
        assertThat(Reservation.externalPatientId(42).length()).isLessThanOrEqualTo(100);
    }

    @Test
    void losInstantesSeGuardanALaPrecisionDeLaBaseYUnVencimientoConNanosegundosNoDifiere() {
        // La cátedra real informa el vencimiento con nanosegundos (2026-11-21T17:02:25.905856025Z).
        var conNanos = Instant.parse("2026-11-21T17:02:25.905856025Z");

        var r = nueva().held("hold-1", "process-1", conNanos, T1.plusNanos(789)).waitingForPhone(conNanos, T1.plusNanos(789));

        assertThat(r.expiresAt()).isEqualTo(Instant.parse("2026-11-21T17:02:25.905856Z"));
        assertThat(r.updatedAt().getNano() % 1000).as("sin nanosegundos sueltos").isZero();
        assertThat(r.createdAt().getNano() % 1000).isZero();
    }

    @Test
    void toStringNoImprimeLosNombresDelPaciente() {
        var texto = nueva().toString();

        assertThat(texto).doesNotContain("Juan", "Pérez").contains("STARTING").contains("218");
    }
}
