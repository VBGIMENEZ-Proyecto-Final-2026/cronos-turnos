package ar.edu.um.cronos.domain;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Un horario semanal de atención de un profesional, tal como lo informa el catálogo: un día de la semana, desde
 * {@code startTime} hasta {@code endTime}, partido en turnos de {@code slotDurationMinutes}. Las horas son las
 * locales del consultorio, sin zona. Cronos no guarda este dato: lo pide al catálogo cada vez (enunciado 3).
 */
public record WeeklySchedule(
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        int slotDurationMinutes,
        boolean enabled) {

    public WeeklySchedule {
        Objects.requireNonNull(dayOfWeek, "dayOfWeek");
        Objects.requireNonNull(startTime, "startTime");
        Objects.requireNonNull(endTime, "endTime");
    }
}
