package ar.edu.um.cronos.adapters.out.rest;

import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.OccupancyClient;
import ar.edu.um.cronos.domain.Occupancy;
import ar.edu.um.cronos.domain.OccupancyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pide a la cátedra las ocupaciones de un profesional ({@code GET /api/appointment-occupancies}, referencia de
 * integración 8). Usa el JWT técnico del proyecto, que ya lleva el {@code RestClient}; reintenta solo ante fallas
 * transitorias y nunca registra el token.
 */
public class CatedraOccupancyClient implements OccupancyClient {

    private static final Logger log = LoggerFactory.getLogger(CatedraOccupancyClient.class);

    private final RestClient restClient;
    private final RetryTemplate retryTemplate;

    /** @param restClient ya lleva la URL base, el JWT técnico, los timeouts y no sigue redirecciones */
    public CatedraOccupancyClient(RestClient restClient, RetryTemplate retryTemplate) {
        this.restClient = restClient;
        this.retryTemplate = retryTemplate;
    }

    @Override
    public List<Occupancy> findOccupancies(long professionalId, LocalDate from, LocalDate to) {
        OccupanciesJson response;
        try {
            response = retryTemplate.execute(() -> restClient.get()
                    .uri(uri -> uri.path("/api/appointment-occupancies")
                            .queryParam("professionalId", professionalId)
                            .queryParam("from", from)
                            .queryParam("to", to)
                            .build())
                    .retrieve()
                    .body(OccupanciesJson.class));
        } catch (RetryException e) {
            throw translate(e.getLastException(), professionalId);
        }
        return toOccupancies(response);
    }

    private RuntimeException translate(Throwable t, long professionalId) {
        if (t instanceof RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = RestFailures.codeOf(e);
            if (status == 401 || status == 403) {
                log.warn("La cátedra rechazó la credencial técnica (HTTP {}, code={}): el token es inválido o "
                        + "venció; se rota en el .env", status, code);
            } else {
                log.warn("La cátedra respondió HTTP {} (code={}) al pedir las ocupaciones del profesional {}",
                        status, code, professionalId);
            }
            return new CatedraUnavailableException("La cátedra respondió HTTP " + status, e);
        }
        if (t instanceof ResourceAccessException e) {
            log.warn("No se pudo conectar con la cátedra al pedir las ocupaciones: {}", e.getClass().getSimpleName());
            return new CatedraUnavailableException("La cátedra no está disponible (error de red o timeout)", e);
        }
        if (t instanceof RestClientException e) {
            log.warn("La respuesta de la cátedra a las ocupaciones no se pudo interpretar");
            return new CatedraUnavailableException("La respuesta de la cátedra no se pudo interpretar", e);
        }
        throw new IllegalStateException("Falla inesperada al pedir las ocupaciones a la cátedra", t);
    }

    private static List<Occupancy> toOccupancies(OccupanciesJson response) {
        if (response == null) {
            throw new CatedraUnavailableException("La cátedra respondió sin cuerpo", null);
        }
        List<Occupancy> result = new ArrayList<>();
        if (response.occupancies() == null) {
            return result;
        }
        for (OccupanciesJson.OccupancyJson o : response.occupancies()) {
            if (o == null || o.date() == null || o.startTime() == null || o.status() == null) {
                throw new CatedraUnavailableException("La cátedra respondió con una ocupación inválida", null);
            }
            OccupancyStatus status;
            try {
                status = OccupancyStatus.valueOf(o.status());
            } catch (IllegalArgumentException e) {
                // Un estado que cronos no conoce no es seguro de interpretar: es mejor fallar que ofrecer un
                // turno que podría estar tomado.
                throw new CatedraUnavailableException("La cátedra respondió con un estado de ocupación desconocido",
                        e);
            }
            result.add(new Occupancy(o.date(), o.startTime(), status, o.expiresAt()));
        }
        return result;
    }

    /** Parte del JSON de la cátedra que cronos usa; los campos que no conoce se ignoran. */
    record OccupanciesJson(List<OccupancyJson> occupancies) {

        record OccupancyJson(LocalDate date, LocalTime startTime, String status, Instant expiresAt) {
        }
    }
}
