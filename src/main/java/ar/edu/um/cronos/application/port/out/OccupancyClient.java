package ar.edu.um.cronos.application.port.out;

import ar.edu.um.cronos.domain.Occupancy;

import java.time.LocalDate;
import java.util.List;

/** Lo que cronos le pide al servicio central de la cátedra sobre los turnos ya tomados. */
public interface OccupancyClient {

    /**
     * Los turnos no disponibles de un profesional entre dos fechas (ambas inclusivas): reservas confirmadas y
     * holds activos. Una lista vacía es una respuesta válida.
     *
     * @throws CatedraUnavailableException si la cátedra no responde o rechaza la credencial técnica
     */
    List<Occupancy> findOccupancies(long professionalId, LocalDate from, LocalDate to);
}
