package ar.edu.um.cronos.application.availability;

import ar.edu.um.cronos.application.port.out.CatalogClient;
import ar.edu.um.cronos.application.port.out.OccupancyClient;
import ar.edu.um.cronos.domain.AvailabilityCalculator;
import ar.edu.um.cronos.domain.Occupancy;
import ar.edu.um.cronos.domain.ProfessionalAgenda;
import ar.edu.um.cronos.domain.Slot;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Los turnos libres de un profesional entre dos fechas (enunciado 7): los horarios semanales vigentes que da el
 * catálogo menos las ocupaciones que informa la cátedra.
 *
 * <p>Orden de las consultas, pensado para no hacer trabajo inútil:
 * <ol>
 *   <li>Se valida el rango antes de llamar a nadie.</li>
 *   <li>Se pide la agenda al catálogo. Si el catálogo falla, no se consulta a la cátedra: sin catálogo no se
 *       puede validar la información vigente (enunciado 8).</li>
 *   <li>Si el profesional no atiende en el rango (deshabilitado, sin horarios habilitados o sin ese día de la
 *       semana), se responde vacío sin molestar a la cátedra.</li>
 *   <li>Recién entonces se piden las ocupaciones, una sola vez para todo el rango.</li>
 * </ol>
 *
 * <p>No guarda nada: ni el catálogo ni las ocupaciones.
 */
public class GetAvailability {

    private final CatalogClient catalog;
    private final OccupancyClient occupancies;
    private final Clock clock;
    private final ZoneId clinicZone;
    private final int maxRangeDays;

    /**
     * @param clinicZone   la zona de las horas locales del consultorio, para saber qué turnos ya empezaron
     * @param maxRangeDays el rango más largo que se acepta, en días contando ambos extremos
     */
    public GetAvailability(CatalogClient catalog, OccupancyClient occupancies, Clock clock, ZoneId clinicZone,
                           int maxRangeDays) {
        if (maxRangeDays < 1) {
            throw new IllegalArgumentException("maxRangeDays debe ser al menos 1");
        }
        this.catalog = catalog;
        this.occupancies = occupancies;
        this.clock = clock;
        this.clinicZone = clinicZone;
        this.maxRangeDays = maxRangeDays;
    }

    /**
     * @param userToken el JWT del usuario, que se reenvía al catálogo sin modificar
     * @throws InvalidDateRangeException si {@code from} es posterior a {@code to} o el rango supera el máximo
     */
    public Availability execute(long professionalId, LocalDate from, LocalDate to, String userToken) {
        validate(from, to);

        ProfessionalAgenda agenda = catalog.findAgenda(professionalId, userToken);

        if (AvailabilityCalculator.grid(agenda, from, to).isEmpty()) {
            return new Availability(professionalId, from, to, List.of());
        }

        List<Occupancy> taken = occupancies.findOccupancies(professionalId, from, to);
        LocalDateTime now = LocalDateTime.now(clock.withZone(clinicZone));
        List<Slot> free = AvailabilityCalculator.free(agenda, taken, from, to, now);
        return new Availability(professionalId, from, to, free);
    }

    private void validate(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new InvalidDateRangeException("La fecha inicial no puede ser posterior a la final");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > maxRangeDays) {
            throw new InvalidDateRangeException("El rango no puede superar " + maxRangeDays + " días");
        }
    }
}
