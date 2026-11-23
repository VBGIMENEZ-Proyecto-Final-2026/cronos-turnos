package ar.edu.um.cronos.domain;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * Calcula los turnos libres de un profesional (enunciado 7): la grilla de sus horarios semanales habilitados
 * menos lo que la cátedra informa como ocupado. Es una función pura: no consulta a nadie, así que se prueba sin
 * Spring ni red.
 *
 * <p>Reglas:
 * <ul>
 *   <li>Un profesional deshabilitado no ofrece turnos, ni un horario deshabilitado.</li>
 *   <li>La grilla va de {@code startTime} a {@code endTime} cada {@code slotDurationMinutes}; solo cuentan los
 *       turnos que entran completos (un turno que terminaría después de {@code endTime} no existe).</li>
 *   <li>Un horario con duración no positiva se ignora: no hay grilla posible y evita un ciclo infinito.</li>
 *   <li>Un turno ocupado ({@code CONFIRMED} o {@code HELD}) no se ofrece, se identifica por fecha y hora de
 *       inicio.</li>
 *   <li>Un turno que ya empezó no se ofrece.</li>
 *   <li>Si dos horarios del mismo día se superponen y generan el mismo turno, se ofrece una sola vez.</li>
 * </ul>
 */
public final class AvailabilityCalculator {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private AvailabilityCalculator() {
    }

    /**
     * Todos los turnos que la agenda permite en el rango, sin restar ocupaciones ni descartar los pasados. Sirve
     * para saber si vale la pena consultar a la cátedra.
     */
    public static List<Slot> grid(ProfessionalAgenda agenda, LocalDate from, LocalDate to) {
        if (!agenda.enabled()) {
            return List.of();
        }
        // Clave: fecha y hora de inicio. Ordena y descarta duplicados de horarios superpuestos.
        TreeMap<LocalDateTime, Slot> slots = new TreeMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            for (WeeklySchedule schedule : agenda.schedules()) {
                if (schedule.enabled() && schedule.dayOfWeek() == date.getDayOfWeek()) {
                    addSlotsOf(schedule, date, slots);
                }
            }
        }
        return new ArrayList<>(slots.values());
    }

    /**
     * Los turnos libres: la grilla menos las ocupaciones y menos los que ya empezaron.
     *
     * @param now el momento actual en la hora local del consultorio
     */
    public static List<Slot> free(ProfessionalAgenda agenda, Collection<Occupancy> occupancies,
                                  LocalDate from, LocalDate to, LocalDateTime now) {
        Set<LocalDateTime> taken = new HashSet<>();
        for (Occupancy occupancy : occupancies) {
            taken.add(LocalDateTime.of(occupancy.date(), occupancy.startTime()));
        }
        return grid(agenda, from, to).stream()
                .filter(slot -> {
                    LocalDateTime start = LocalDateTime.of(slot.date(), slot.startTime());
                    return start.isAfter(now) && !taken.contains(start);
                })
                .toList();
    }

    private static void addSlotsOf(WeeklySchedule schedule, LocalDate date, TreeMap<LocalDateTime, Slot> slots) {
        int duration = schedule.slotDurationMinutes();
        if (duration <= 0) {
            return;
        }
        int end = minutes(schedule.endTime());
        for (int start = minutes(schedule.startTime()); start + duration <= end && start < MINUTES_PER_DAY;
             start += duration) {
            LocalTime startTime = LocalTime.of(start / 60, start % 60);
            LocalTime endTime = start + duration >= MINUTES_PER_DAY
                    ? LocalTime.MAX.withNano(0)
                    : LocalTime.of((start + duration) / 60, (start + duration) % 60);
            slots.putIfAbsent(LocalDateTime.of(date, startTime), new Slot(date, startTime, endTime));
        }
    }

    private static int minutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
