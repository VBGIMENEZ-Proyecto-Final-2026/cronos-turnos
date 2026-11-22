package ar.edu.um.cronos.domain;

import java.util.Objects;

/**
 * Lo que la cátedra informa sobre un proceso de reserva por Kafka (referencia de integración, 15.4 a 15.10). Son datos
 * ya interpretados y validados: este tipo no sabe de JSON ni de Kafka.
 *
 * <p>{@code eventId} es la clave de idempotencia: la entrega es "al menos una vez", así que el mismo evento puede
 * llegar dos veces y no debe repetir ningún efecto. {@code holdId} es el hold al que se refiere el evento; si trae uno
 * distinto del que cronos conoce para ese proceso, el evento no se aplica.
 */
public sealed interface ReservationEvent {

    String eventId();

    String reservationProcessId();

    /** El hold del evento; {@code null} si el mensaje no lo traía. */
    String holdId();

    /** El {@code eventType} tal como lo escribe la cátedra: se guarda como evidencia de qué se aplicó. */
    String type();

    /** {@code AdditionalInformationRequested}: la cátedra pide el teléfono. Su {@code eventId} se responde después. */
    record InformationRequested(String eventId, String reservationProcessId, String holdId)
            implements ReservationEvent {
        public InformationRequested {
            required(eventId, reservationProcessId);
        }

        @Override
        public String type() {
            return "AdditionalInformationRequested";
        }
    }

    /** {@code AdditionalInformationRejected}: el teléfono no sirvió; el proceso sigue disponible hasta vencer. */
    record InformationRejected(String eventId, String reservationProcessId, String holdId, String reason)
            implements ReservationEvent {
        public InformationRejected {
            required(eventId, reservationProcessId);
        }

        @Override
        public String type() {
            return "AdditionalInformationRejected";
        }
    }

    /** {@code AppointmentConfirmed}: la reserva quedó confirmada con este id en la cátedra. */
    record AppointmentConfirmed(String eventId, String reservationProcessId, String holdId, long reservationId)
            implements ReservationEvent {
        public AppointmentConfirmed {
            required(eventId, reservationProcessId);
        }

        @Override
        public String type() {
            return "AppointmentConfirmed";
        }
    }

    /** {@code AppointmentProcessExpired}: el hold venció. */
    record ProcessExpired(String eventId, String reservationProcessId, String holdId) implements ReservationEvent {
        public ProcessExpired {
            required(eventId, reservationProcessId);
        }

        @Override
        public String type() {
            return "AppointmentProcessExpired";
        }
    }

    /** {@code AppointmentProcessInvalid}: la cátedra detectó una inconsistencia (el motivo es un código). */
    record ProcessInvalid(String eventId, String reservationProcessId, String holdId, String reason)
            implements ReservationEvent {
        public ProcessInvalid {
            required(eventId, reservationProcessId);
        }

        @Override
        public String type() {
            return "AppointmentProcessInvalid";
        }
    }

    /** {@code AppointmentCancelled}: la reserva confirmada se canceló. */
    record AppointmentCancelled(String eventId, String reservationProcessId, String holdId)
            implements ReservationEvent {
        public AppointmentCancelled {
            required(eventId, reservationProcessId);
        }

        @Override
        public String type() {
            return "AppointmentCancelled";
        }
    }

    private static void required(String eventId, String reservationProcessId) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(reservationProcessId, "reservationProcessId");
        if (eventId.isBlank() || reservationProcessId.isBlank()) {
            throw new IllegalArgumentException("eventId y reservationProcessId no pueden estar vacíos");
        }
    }
}
