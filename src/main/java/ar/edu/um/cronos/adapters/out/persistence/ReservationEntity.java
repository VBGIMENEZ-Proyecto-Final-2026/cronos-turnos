package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.domain.Reservation;
import ar.edu.um.cronos.domain.ReservationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Fila de {@code reservation_process}. Es un detalle de la persistencia: el resto usa {@code domain.Reservation}.
 * {@code version} es el bloqueo optimista: si otro cambió el proceso desde que se lo leyó, guardar falla.
 */
@Entity
@Table(name = "reservation_process")
class ReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    @Column(name = "reservation_process_id")
    private String reservationProcessId;

    @Column(name = "hold_id")
    private String holdId;

    @Column(name = "professional_id", nullable = false)
    private long professionalId;

    @Column(name = "professional_first_name")
    private String professionalFirstName;

    @Column(name = "professional_last_name")
    private String professionalLastName;

    @Column(name = "appointment_date", nullable = false)
    private LocalDate date;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "patient_first_name", nullable = false)
    private String patientFirstName;

    @Column(name = "patient_last_name", nullable = false)
    private String patientLastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "failure_code")
    private String failureCode;

    @Column(name = "request_event_id")
    private String requestEventId;

    @Column(name = "last_rejection_reason")
    private String lastRejectionReason;

    @Column(name = "catedra_reservation_id")
    private Long catedraReservationId;

    /** Cuándo se mandó el teléfono. El número no se guarda en ninguna parte. */
    @Column(name = "phone_submitted_at")
    private Instant phoneSubmittedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReservationEntity() {
        // Lo exige JPA.
    }

    static ReservationEntity from(Reservation r) {
        ReservationEntity e = new ReservationEntity();
        e.id = r.id();
        e.userId = r.userId();
        e.reservationProcessId = r.reservationProcessId();
        e.holdId = r.holdId();
        e.professionalId = r.professionalId();
        e.professionalFirstName = r.professionalFirstName();
        e.professionalLastName = r.professionalLastName();
        e.date = r.date();
        e.startTime = r.startTime();
        e.endTime = r.endTime();
        e.patientFirstName = r.patientFirstName();
        e.patientLastName = r.patientLastName();
        e.status = r.status();
        e.expiresAt = r.expiresAt();
        e.failureCode = r.failureCode();
        e.requestEventId = r.requestEventId();
        e.lastRejectionReason = r.lastRejectionReason();
        e.catedraReservationId = r.catedraReservationId();
        e.phoneSubmittedAt = r.phoneSubmittedAt();
        e.version = r.version();
        e.createdAt = r.createdAt();
        e.updatedAt = r.updatedAt();
        return e;
    }

    Reservation toDomain() {
        return new Reservation(id, userId, reservationProcessId, holdId, professionalId, professionalFirstName,
                professionalLastName, date, startTime, endTime, patientFirstName, patientLastName, status, expiresAt,
                failureCode, requestEventId, lastRejectionReason, catedraReservationId, phoneSubmittedAt, version, createdAt,
                updatedAt);
    }
}
