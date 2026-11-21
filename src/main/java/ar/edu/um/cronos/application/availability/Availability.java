package ar.edu.um.cronos.application.availability;

import ar.edu.um.cronos.domain.Slot;

import java.time.LocalDate;
import java.util.List;

/** Los turnos libres de un profesional en un rango de fechas. */
public record Availability(long professionalId, LocalDate from, LocalDate to, List<Slot> slots) {
}
