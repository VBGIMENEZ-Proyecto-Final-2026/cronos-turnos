package ar.edu.um.cronos.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Un proceso de reserva de un usuario: el turno que pidió, el hold y el proceso que la cátedra le asignó y en qué
 * estado va. Cada proceso queda asociado al usuario que lo inició (enunciado 4.2): la cátedra solo conoce la cuenta
 * técnica del proyecto, así que la propiedad por usuario vive acá.
 *
 * <p>Es inmutable: cada cambio de estado devuelve otro valor y respeta {@link ReservationStatus#canTransitionTo}.
 * Los datos del profesional son una foto descriptiva de cuando se reservó, no el catálogo vigente (enunciado 4.2).
 *
 * @param id                   {@code null} hasta que se persiste
 * @param userId               el usuario final dueño del proceso
 * @param reservationProcessId el proceso en la cátedra; {@code null} hasta que existe el hold
 * @param holdId               el hold en la cátedra; {@code null} hasta que existe
 * @param version              para detectar escrituras simultáneas sobre el mismo proceso
 */
public record Reservation(
        Long id,
        long userId,
        String reservationProcessId,
        String holdId,
        long professionalId,
        String professionalFirstName,
        String professionalLastName,
        LocalDate date,
        LocalTime startTime,
        LocalTime endTime,
        String patientFirstName,
        String patientLastName,
        ReservationStatus status,
        Instant expiresAt,
        String failureCode,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    public Reservation {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
        Objects.requireNonNull(patientFirstName, "patientFirstName");
        Objects.requireNonNull(patientLastName, "patientLastName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        // A la precisión de la base (microsegundos): lo que se devuelve al guardar es idéntico a lo que se lee después.
        // La cátedra informa el vencimiento con nanosegundos (se vio con la cátedra real) y PostgreSQL los trunca.
        expiresAt = expiresAt == null ? null : expiresAt.truncatedTo(ChronoUnit.MICROS);
        createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
        updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /** Un proceso nuevo: registrado en cronos, todavía sin hold. */
    public static Reservation start(long userId, long professionalId, String professionalFirstName,
                                    String professionalLastName, Slot slot, String patientFirstName,
                                    String patientLastName, Instant now) {
        return new Reservation(null, userId, null, null, professionalId, professionalFirstName,
                professionalLastName, slot.date(), slot.startTime(), slot.endTime(), patientFirstName,
                patientLastName, ReservationStatus.STARTING, null, null, 0, now, now);
    }

    /** La cátedra creó el hold. */
    public Reservation held(String holdId, String reservationProcessId, Instant expiresAt, Instant now) {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(reservationProcessId, "reservationProcessId");
        return transition(ReservationStatus.HELD, now, null, holdId, reservationProcessId, expiresAt);
    }

    /** El hold se confirmó inicialmente; la cátedra espera la información adicional. */
    public Reservation waitingForPhone(Instant expiresAt, Instant now) {
        return transition(ReservationStatus.WAITING_FOR_PHONE, now, null, holdId, reservationProcessId,
                expiresAt != null ? expiresAt : this.expiresAt);
    }

    public Reservation expired(Instant now) {
        return transition(ReservationStatus.EXPIRED, now, null, holdId, reservationProcessId, expiresAt);
    }

    /** No se pudo completar. {@code code} es un motivo estable, nunca un texto de la cátedra ni un dato del usuario. */
    public Reservation failed(String code, Instant now) {
        return transition(ReservationStatus.FAILED, now, code, holdId, reservationProcessId, expiresAt);
    }

    /** Cualquier otra transición permitida (resultado final por Kafka, cancelación). */
    public Reservation moveTo(ReservationStatus target, Instant now) {
        return transition(target, now, failureCode, holdId, reservationProcessId, expiresAt);
    }

    private Reservation transition(ReservationStatus target, Instant now, String failureCode, String holdId,
                                   String reservationProcessId, Instant expiresAt) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Transición no permitida: " + status + " -> " + target);
        }
        return new Reservation(id, userId, reservationProcessId, holdId, professionalId, professionalFirstName,
                professionalLastName, date, startTime, endTime, patientFirstName, patientLastName, target, expiresAt,
                failureCode, version, createdAt, now);
    }

    /** El identificador con el que la cátedra conoce al paciente: opaco, no revela el login del usuario. */
    public static String externalPatientId(long userId) {
        return "user-" + userId;
    }

    @Override
    public String toString() {
        // Sin los nombres del paciente: un dato personal no tiene por qué llegar a un log por accidente.
        return "Reservation[id=" + id + ", userId=" + userId + ", process=" + reservationProcessId + ", professional="
                + professionalId + ", " + date + " " + startTime + ", status=" + status + "]";
    }
}
