package ar.edu.um.cronos.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Un turno que la cátedra informa como no disponible (referencia de integración, 8). Se identifica por la fecha y
 * la hora de inicio. Un {@code HELD} tiene vencimiento; un {@code CONFIRMED}, no.
 */
public record Occupancy(LocalDate date, LocalTime startTime, OccupancyStatus status, Instant expiresAt) {

    public Occupancy {
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(status, "status");
    }
}
