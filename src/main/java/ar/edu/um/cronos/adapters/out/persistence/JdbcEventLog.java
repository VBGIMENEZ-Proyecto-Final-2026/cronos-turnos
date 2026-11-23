package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.application.port.out.ProcessedEvents;
import ar.edu.um.cronos.application.port.out.UnmatchedEvents;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Las tablas {@code processed_event} y {@code unmatched_event} (migración V3). Usa {@code INSERT ... ON CONFLICT DO
 * NOTHING}: la base decide quién llegó primero, sin lecturas previas que dos hilos puedan ganar a la vez. Si hay una
 * transacción en curso (la de {@code ProcessReservationEvent}), el insert forma parte de ella.
 */
@Component
class JdbcEventLog implements ProcessedEvents, UnmatchedEvents {

    private final JdbcTemplate jdbc;

    JdbcEventLog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean markProcessed(String eventId, String eventType, String reservationProcessId, Instant at) {
        return jdbc.update("INSERT INTO processed_event (event_id, event_type, reservation_process_id, processed_at) "
                        + "VALUES (?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType, reservationProcessId, utc(at)) == 1;
    }

    @Override
    public boolean park(String eventId, String eventType, String reservationProcessId, String reason, Instant at) {
        return jdbc.update("INSERT INTO unmatched_event (event_id, event_type, reservation_process_id, reason, "
                        + "received_at) VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING",
                eventId, eventType, reservationProcessId, reason, utc(at)) == 1;
    }

    private static OffsetDateTime utc(Instant at) {
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }
}
