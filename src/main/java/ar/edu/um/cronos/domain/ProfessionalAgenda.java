package ar.edu.um.cronos.domain;

import java.util.List;

/**
 * Lo que cronos necesita saber de un profesional para armar su disponibilidad: si atiende y sus horarios
 * semanales. No es una copia del catálogo: vive lo que dura el pedido (enunciado 3 y 4.2).
 *
 * @param professionalId id del profesional en el catálogo
 * @param enabled        {@code false} si el profesional está deshabilitado: no se le ofrecen turnos
 */
public record ProfessionalAgenda(long professionalId, boolean enabled, List<WeeklySchedule> schedules) {

    public ProfessionalAgenda {
        schedules = schedules == null ? List.of() : List.copyOf(schedules);
    }
}
