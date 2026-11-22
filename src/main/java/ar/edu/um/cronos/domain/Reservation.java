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
 * @param failureCode          el motivo de un {@code FAILED} o un {@code INVALID}: un código estable, nunca un texto
 * @param requestEventId       el {@code eventId} del último {@code AdditionalInformationRequested}: hay que mandarlo
 *                             como {@code requestEventId} al responder el teléfono; {@code null} hasta que llega
 * @param lastRejectionReason  el código del último {@code AdditionalInformationRejected}. Un rechazo no termina el
 *                             proceso (referencia 15.7): sigue esperando hasta {@code expiresAt}
 * @param catedraReservationId el id de la reserva confirmada en la cátedra; {@code null} hasta {@code CONFIRMED}
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
        String requestEventId,
        String lastRejectionReason,
        Long catedraReservationId,
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
                patientLastName, ReservationStatus.STARTING, null, null, null, null, null, 0, now, now);
    }

    /** La cátedra creó el hold. */
    public Reservation held(String holdId, String reservationProcessId, Instant expiresAt, Instant now) {
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(reservationProcessId, "reservationProcessId");
        return transition(ReservationStatus.HELD, now, holdId, reservationProcessId, expiresAt);
    }

    /** El hold se confirmó inicialmente; la cátedra espera la información adicional. */
    public Reservation waitingForPhone(Instant expiresAt, Instant now) {
        return transition(ReservationStatus.WAITING_FOR_PHONE, now, holdId, reservationProcessId,
                expiresAt != null ? expiresAt : this.expiresAt);
    }

    public Reservation expired(Instant now) {
        return transition(ReservationStatus.EXPIRED, now);
    }

    /** No se pudo completar. {@code code} es un motivo estable, nunca un texto de la cátedra ni un dato del usuario. */
    public Reservation failed(String code, Instant now) {
        return transition(ReservationStatus.FAILED, now).withFailureCode(code);
    }

    // ---- lo que informa la cátedra por Kafka ---------------------------------------------------------------------

    /**
     * {@code AdditionalInformationRequested}: la cátedra pide el teléfono. Se guarda su {@code eventId} (hace falta para
     * responder). <b>No cambia el estado</b>: pasar a {@code WAITING_FOR_PHONE} lo hace la confirmación REST. Si llegó
     * otro pedido antes, se reemplaza por el más nuevo.
     */
    public Reservation informationRequested(String requestEventId, Instant now) {
        Objects.requireNonNull(requestEventId, "requestEventId");
        requireAwaitingResult("AdditionalInformationRequested");
        return copy(status, now).withRequestEventId(requestEventId);
    }

    /**
     * {@code AdditionalInformationRejected}: el teléfono no sirvió. <b>El proceso sigue vivo</b> hasta {@code expiresAt}
     * y se puede mandar otro (referencia 15.7); solo se guarda el motivo. Un {@code reason} que no sea un código estable
     * se guarda como {@value #OTHER_REASON}.
     */
    public Reservation informationRejected(String reason, Instant now) {
        requireAwaitingResult("AdditionalInformationRejected");
        return copy(status, now).withLastRejectionReason(stableCode(reason));
    }

    /** {@code AppointmentConfirmed}: la reserva quedó confirmada en la cátedra. Solo desde {@code WAITING_FOR_PHONE}. */
    public Reservation confirmed(long catedraReservationId, Instant now) {
        return transition(ReservationStatus.CONFIRMED, now).withCatedraReservationId(catedraReservationId);
    }

    /** {@code AppointmentProcessInvalid}: la cátedra detectó una inconsistencia. El motivo queda en el código. */
    public Reservation invalid(String reason, Instant now) {
        return transition(ReservationStatus.INVALID, now).withFailureCode(stableCode(reason));
    }

    /** {@code AppointmentCancelled}: solo una reserva confirmada puede cancelarse. */
    public Reservation cancelled(Instant now) {
        return transition(ReservationStatus.CANCELLED, now);
    }

    // ---- ayudas ---------------------------------------------------------------------------------------------------

    /** Código que se usa cuando la cátedra manda un motivo que no tiene la forma de un código estable. */
    static final String OTHER_REASON = "OTHER";
    private static final java.util.regex.Pattern STABLE_CODE = java.util.regex.Pattern.compile("[A-Z][A-Z0-9_]{0,59}");

    /** Solo se guarda un código estable (MAYÚSCULAS_Y_GUIONES, hasta 60): nunca un texto libre ni un dato personal. */
    private static String stableCode(String reason) {
        return reason != null && STABLE_CODE.matcher(reason).matches() ? reason : OTHER_REASON;
    }

    /** Los eventos de la cátedra solo tienen sentido sobre un proceso que ya tiene hold y todavía no terminó. */
    private void requireAwaitingResult(String event) {
        if (status != ReservationStatus.HELD && status != ReservationStatus.WAITING_FOR_PHONE) {
            throw new IllegalStateException(event + " no corresponde a un proceso en " + status);
        }
    }

    private Reservation transition(ReservationStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalStateException("Transición no permitida: " + status + " -> " + target);
        }
        return copy(target, now);
    }

    private Reservation transition(ReservationStatus target, Instant now, String holdId,
                                   String reservationProcessId, Instant expiresAt) {
        Reservation next = transition(target, now);
        return new Reservation(next.id, next.userId, reservationProcessId, holdId, next.professionalId,
                next.professionalFirstName, next.professionalLastName, next.date, next.startTime, next.endTime,
                next.patientFirstName, next.patientLastName, next.status, expiresAt, next.failureCode,
                next.requestEventId, next.lastRejectionReason, next.catedraReservationId, next.version,
                next.createdAt, next.updatedAt);
    }

    private Reservation copy(ReservationStatus newStatus, Instant now) {
        return new Reservation(id, userId, reservationProcessId, holdId, professionalId, professionalFirstName,
                professionalLastName, date, startTime, endTime, patientFirstName, patientLastName, newStatus,
                expiresAt, failureCode, requestEventId, lastRejectionReason, catedraReservationId, version,
                createdAt, now);
    }

    private Reservation withFailureCode(String code) {
        return new Reservation(id, userId, reservationProcessId, holdId, professionalId, professionalFirstName,
                professionalLastName, date, startTime, endTime, patientFirstName, patientLastName, status, expiresAt,
                code, requestEventId, lastRejectionReason, catedraReservationId, version, createdAt, updatedAt);
    }

    private Reservation withRequestEventId(String eventId) {
        return new Reservation(id, userId, reservationProcessId, holdId, professionalId, professionalFirstName,
                professionalLastName, date, startTime, endTime, patientFirstName, patientLastName, status, expiresAt,
                failureCode, eventId, lastRejectionReason, catedraReservationId, version, createdAt, updatedAt);
    }

    private Reservation withLastRejectionReason(String reason) {
        return new Reservation(id, userId, reservationProcessId, holdId, professionalId, professionalFirstName,
                professionalLastName, date, startTime, endTime, patientFirstName, patientLastName, status, expiresAt,
                failureCode, requestEventId, reason, catedraReservationId, version, createdAt, updatedAt);
    }

    private Reservation withCatedraReservationId(long reservationId) {
        return new Reservation(id, userId, reservationProcessId, holdId, professionalId, professionalFirstName,
                professionalLastName, date, startTime, endTime, patientFirstName, patientLastName, status, expiresAt,
                failureCode, requestEventId, lastRejectionReason, reservationId, version, createdAt, updatedAt);
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
