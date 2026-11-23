package ar.edu.um.cronos.domain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static ar.edu.um.cronos.domain.ReservationStatus.CANCELLED;
import static ar.edu.um.cronos.domain.ReservationStatus.CONFIRMED;
import static ar.edu.um.cronos.domain.ReservationStatus.EXPIRED;
import static ar.edu.um.cronos.domain.ReservationStatus.FAILED;
import static ar.edu.um.cronos.domain.ReservationStatus.HELD;
import static ar.edu.um.cronos.domain.ReservationStatus.INVALID;
import static ar.edu.um.cronos.domain.ReservationStatus.STARTING;
import static ar.edu.um.cronos.domain.ReservationStatus.WAITING_FOR_PHONE;
import static org.assertj.core.api.Assertions.assertThat;

/** La máquina de estados completa: cada transición permitida y, sobre todo, todas las que no lo son. */
class ReservationStatusTest {

    /** Lo que el diagrama del enum permite. Todo lo que no esté acá tiene que estar prohibido. */
    private static final Map<ReservationStatus, Set<ReservationStatus>> PERMITIDAS = Map.of(
            STARTING, EnumSet.of(HELD, FAILED),
            HELD, EnumSet.of(WAITING_FOR_PHONE, EXPIRED, INVALID, FAILED),
            WAITING_FOR_PHONE, EnumSet.of(CONFIRMED, EXPIRED, INVALID, FAILED),
            CONFIRMED, EnumSet.of(CANCELLED),
            EXPIRED, EnumSet.noneOf(ReservationStatus.class),
            INVALID, EnumSet.noneOf(ReservationStatus.class),
            CANCELLED, EnumSet.noneOf(ReservationStatus.class),
            FAILED, EnumSet.noneOf(ReservationStatus.class));

    @Test
    void cadaParDeEstadosSeDecideExactamenteComoDiceElDiagrama() {
        for (ReservationStatus desde : ReservationStatus.values()) {
            for (ReservationStatus hasta : ReservationStatus.values()) {
                assertThat(desde.canTransitionTo(hasta))
                        .as(desde + " -> " + hasta)
                        .isEqualTo(PERMITIDAS.get(desde).contains(hasta));
            }
        }
    }

    @Test
    void laTablaDePruebaCubreTodosLosEstados() {
        assertThat(PERMITIDAS.keySet()).containsExactlyInAnyOrder(ReservationStatus.values());
    }

    @Test
    void unRechazoDelTelefonoNoEsUnEstadoPorqueElProcesoSigueVivoHastaQueVence() {
        // Referencia 15.7: tras AdditionalInformationRejected el proceso continúa disponible hasta expiresAt y puede
        // enviarse otro evento válido con un eventId nuevo. Un estado REJECTED final sería un error.
        assertThat(ReservationStatus.values()).extracting(Enum::name).doesNotContain("REJECTED");
    }

    @Test
    void esperandoElTelefonoSoloSeSaleConUnResultadoFinal() {
        assertThat(PERMITIDAS.get(WAITING_FOR_PHONE)).containsExactlyInAnyOrder(CONFIRMED, EXPIRED, INVALID, FAILED);
        assertThat(WAITING_FOR_PHONE.isFinal()).isFalse();
    }

    @Test
    void unEstadoNuncaPasaASiMismo() {
        for (ReservationStatus estado : ReservationStatus.values()) {
            assertThat(estado.canTransitionTo(estado)).as(estado.name()).isFalse();
        }
    }

    @Test
    void losEstadosFinalesNoAdmitenNingunaTransicion() {
        for (ReservationStatus final_ : Set.of(EXPIRED, INVALID, CANCELLED, FAILED)) {
            assertThat(final_.isFinal()).as(final_.name()).isTrue();
            for (ReservationStatus otro : ReservationStatus.values()) {
                assertThat(final_.canTransitionTo(otro)).as(final_ + " -> " + otro).isFalse();
            }
        }
    }

    @Test
    void ningunEstadoNoFinalEsFinal() {
        for (ReservationStatus estado : Set.of(STARTING, HELD, WAITING_FOR_PHONE, CONFIRMED)) {
            assertThat(estado.isFinal()).as(estado.name()).isFalse();
        }
    }

    @Test
    void unaReservaConfirmadaSoloPuedeCancelarse() {
        assertThat(EnumSet.allOf(ReservationStatus.class).stream().filter(CONFIRMED::canTransitionTo))
                .containsExactly(CANCELLED);
    }

    @Test
    void siempreSeLlegaAUnFinalDesdeElInicio() {
        // No hay estados sin salida que no sean finales: nada queda "imposible de recuperar" (enunciado 8).
        for (ReservationStatus estado : ReservationStatus.values()) {
            assertThat(estado.isFinal() || !PERMITIDAS.get(estado).isEmpty()).as(estado.name()).isTrue();
        }
    }

    @Test
    void ocupanElTurnoLosProcesosEnCursoYElConfirmado() {
        assertThat(EnumSet.allOf(ReservationStatus.class).stream().filter(ReservationStatus::occupiesSlot))
                .containsExactlyInAnyOrder(STARTING, HELD, WAITING_FOR_PHONE, CONFIRMED);
    }
}
