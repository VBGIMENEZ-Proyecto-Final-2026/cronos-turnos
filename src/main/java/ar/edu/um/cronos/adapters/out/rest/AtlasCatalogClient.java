package ar.edu.um.cronos.adapters.out.rest;

import ar.edu.um.cronos.application.port.out.CatalogClient;
import ar.edu.um.cronos.application.port.out.CatalogUnavailableException;
import ar.edu.um.cronos.application.port.out.ProfessionalNotFoundException;
import ar.edu.um.cronos.domain.ProfessionalAgenda;
import ar.edu.um.cronos.domain.WeeklySchedule;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Pide a atlas el detalle de un profesional con sus horarios ({@code GET /api/professionals/{id}}, contrato de
 * atlas 3.2). Reenvía el JWT del usuario sin modificarlo (ADR 0004), reintenta solo ante fallas transitorias y
 * traduce los errores por {@code status}. Nunca registra el token.
 */
public class AtlasCatalogClient implements CatalogClient {

    private static final Logger log = LoggerFactory.getLogger(AtlasCatalogClient.class);

    private final RestClient restClient;
    private final RetryTemplate retryTemplate;

    /** @param restClient ya lleva la URL base de atlas, los timeouts y no sigue redirecciones */
    public AtlasCatalogClient(RestClient restClient, RetryTemplate retryTemplate) {
        this.restClient = restClient;
        this.retryTemplate = retryTemplate;
    }

    @Override
    public ProfessionalAgenda findAgenda(long professionalId, String userToken) {
        ProfessionalDetailJson detail;
        try {
            detail = retryTemplate.execute(() -> restClient.get()
                    .uri("/api/professionals/{id}", professionalId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                    .retrieve()
                    .body(ProfessionalDetailJson.class));
        } catch (RetryException e) {
            throw translate(e.getLastException(), professionalId);
        }
        return toAgenda(detail, professionalId);
    }

    private RuntimeException translate(Throwable t, long professionalId) {
        if (t instanceof RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 404) {
                return new ProfessionalNotFoundException(professionalId);
            }
            String code = RestFailures.codeOf(e);
            // 401 o 403: atlas no acepta el token que cronos acaba de validar. Es un problema de configuración
            // (las claves no coinciden), no del usuario: para el cliente, el catálogo no está utilizable.
            LogEvent.warn(log, "atlas.llamada.fallida", "operacion", "consultar_agenda", "profesional", professionalId,
                    "estado", status, "codigo", code,
                    "accion", status == 401 || status == 403 ? "revisar_claves_jwt_de_atlas_y_cronos" : "-");
            return new CatalogUnavailableException("El catálogo respondió HTTP " + status, e);
        }
        if (t instanceof ResourceAccessException e) {
            LogEvent.warn(log, "atlas.llamada.sin_respuesta", "operacion", "consultar_agenda",
                    "profesional", professionalId, "motivo", e.getClass().getSimpleName());
            return new CatalogUnavailableException("El catálogo no está disponible (error de red o timeout)", e);
        }
        if (t instanceof RestClientException e) {
            LogEvent.warn(log, "atlas.respuesta.ilegible", "operacion", "consultar_agenda",
                    "profesional", professionalId);
            return new CatalogUnavailableException("La respuesta del catálogo no se pudo interpretar", e);
        }
        throw new IllegalStateException("Falla inesperada al pedir la agenda a atlas", t);
    }

    private static ProfessionalAgenda toAgenda(ProfessionalDetailJson detail, long professionalId) {
        if (detail == null || detail.id() == null || detail.id() != professionalId || detail.enabled() == null) {
            throw new CatalogUnavailableException("El catálogo respondió con un profesional inválido", null);
        }
        List<WeeklySchedule> schedules = new ArrayList<>();
        for (ProfessionalDetailJson.ScheduleJson s : detail.schedules() == null ? List.<ProfessionalDetailJson.ScheduleJson>of()
                : detail.schedules()) {
            if (s == null || s.dayOfWeek() == null || s.startTime() == null || s.endTime() == null
                    || s.slotDurationMinutes() == null || s.enabled() == null) {
                throw new CatalogUnavailableException("El catálogo respondió con un horario inválido", null);
            }
            schedules.add(new WeeklySchedule(s.dayOfWeek(), s.startTime(), s.endTime(), s.slotDurationMinutes(),
                    s.enabled()));
        }
        return new ProfessionalAgenda(professionalId, detail.enabled(), detail.firstName(), detail.lastName(),
                schedules);
    }

    /** Parte del JSON de atlas que cronos usa; los campos que no conoce se ignoran. */
    record ProfessionalDetailJson(Long id, String firstName, String lastName, Boolean enabled,
                                  List<ScheduleJson> schedules) {

        record ScheduleJson(DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime,
                            Integer slotDurationMinutes, Boolean enabled) {
        }
    }
}
