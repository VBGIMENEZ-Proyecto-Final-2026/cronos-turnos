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
        assertThatThrownBy(() -> expirada.moveTo(ReservationStatus.CONFIRMED, T1))
                .isInstanceOf(IllegalStateException.class);
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
