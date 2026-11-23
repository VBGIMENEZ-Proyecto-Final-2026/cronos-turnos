package ar.edu.um.cronos.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.ZoneId;

/**
 * Parámetros de la disponibilidad.
 *
 * @param zone         zona de las horas locales del consultorio: sirve para decidir qué turnos ya empezaron. Las
 *                     horas de los horarios y de las ocupaciones no traen zona (referencia de integración, 4)
 * @param maxRangeDays el rango de fechas más largo que se acepta, contando ambos extremos
 */
@ConfigurationProperties("cronos.availability")
public record AvailabilityProperties(
        @DefaultValue("America/Argentina/Buenos_Aires") ZoneId zone,
        @DefaultValue("31") int maxRangeDays) {

    public AvailabilityProperties {
        if (maxRangeDays < 1 || maxRangeDays > 366) {
            throw new IllegalArgumentException("CRONOS_AVAILABILITY_MAX_RANGE_DAYS debe estar entre 1 y 366");
        }
    }
}
