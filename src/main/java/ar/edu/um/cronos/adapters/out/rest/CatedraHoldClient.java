package ar.edu.um.cronos.adapters.out.rest;

import ar.edu.um.cronos.application.port.out.CatedraFailure;
import ar.edu.um.cronos.application.port.out.CatedraRejectedException;
import ar.edu.um.cronos.application.port.out.CatedraUnavailableException;
import ar.edu.um.cronos.application.port.out.HoldGateway;
import ar.edu.um.cronos.logging.LogEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Crea y confirma holds en la cátedra ({@code POST /api/appointment-holds} y {@code .../{holdId}/confirm}, referencia
 * de integración 9 y 10) con el JWT técnico del proyecto, que ya lleva el {@code RestClient}.
 *
 * <p><b>No reintenta nada:</b> son operaciones con efectos y un timeout no prueba que fallaron (referencia, 18.4).
 * Distingue dos cosas que el resto del sistema trata distinto: una respuesta de la cátedra que <em>rechaza</em> la
 * operación ({@link CatedraRejectedException}, se sabe qué pasó) y no saber qué pasó
 * ({@link CatedraUnavailableException}: sin respuesta, error de red o de servidor, credencial rechazada). Decide por
 * {@code status} y {@code code}, nunca por el texto de {@code detail}. Nunca registra el token.
 */
public class CatedraHoldClient implements HoldGateway {

    private static final Logger log = LoggerFactory.getLogger(CatedraHoldClient.class);
    private static final DateTimeFormatter HOUR_MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private final RestClient restClient;

    /** @param restClient ya lleva la URL base, el JWT técnico, los timeouts y no sigue redirecciones */
    public CatedraHoldClient(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public HoldCreated createHold(long professionalId, LocalDate date, LocalTime startTime,
                                  String externalPatientId) {
        HoldCreatedJson response;
        try {
            response = restClient.post().uri("/api/appointment-holds").contentType(MediaType.APPLICATION_JSON)
                    .body(new CreateHoldJson(professionalId, date.toString(), startTime.format(HOUR_MINUTE),
                            externalPatientId))
                    .retrieve().body(HoldCreatedJson.class);
        } catch (RestClientException e) {
            throw translate(e, "crear_hold", "crear el hold");
        }
        if (response == null || blank(response.holdId()) || blank(response.reservationProcessId())) {
            throw new CatedraUnavailableException("La cátedra respondió un hold inválido", null);
        }
        return new HoldCreated(response.holdId(), response.reservationProcessId(), response.expiresAt());
    }

    @Override
    public HoldConfirmed confirmHold(String holdId, String externalPatientId, String patientFirstName,
                                     String patientLastName) {
        HoldConfirmedJson response;
        try {
            response = restClient.post().uri("/api/appointment-holds/{holdId}/confirm", holdId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ConfirmHoldJson(externalPatientId, patientFirstName, patientLastName))
                    .retrieve().body(HoldConfirmedJson.class);
        } catch (RestClientException e) {
            throw translate(e, "confirmar_hold", "confirmar el hold");
        }
        if (response == null || blank(response.reservationProcessId())) {
            throw new CatedraUnavailableException("La cátedra respondió una confirmación inválida", null);
        }
        return new HoldConfirmed(response.reservationProcessId(), response.expiresAt());
    }

    /**
     * @param operationCode el nombre de la operación para el log (se busca con grep)
     * @param operation     la misma operación en palabras, para el mensaje de la excepción
     */
    private RuntimeException translate(RestClientException e, String operationCode, String operation) {
        if (e instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            String code = RestFailures.codeOf(response);
            CatedraFailure failure = failureOf(code);
            // 401, 5xx y un 403 que no es "el hold es de otro" son problemas de credencial o de servidor: no se sabe
            // qué pasó con la operación.
            boolean unknownOutcome = status == 401 || status >= 500
                    || (status == 403 && failure != CatedraFailure.HOLD_OWNER_MISMATCH);
            if (unknownOutcome) {
                if (status == 401 || status == 403) {
                    LogEvent.warn(log, "catedra.credencial.rechazada", "operacion", operationCode, "estado", status,
                            "codigo", code, "accion", "rotar_token_en_env");
                } else {
                    LogEvent.warn(log, "catedra.llamada.fallida", "operacion", operationCode, "estado", status,
                            "codigo", code, "resultado", "se_desconoce_si_se_aplico");
                }
                return new CatedraUnavailableException("La cátedra respondió HTTP " + status, response);
            }
            LogEvent.info(log, "catedra.operacion.rechazada", "operacion", operationCode, "estado", status,
                    "codigo", code, "motivo", failure);
            return new CatedraRejectedException(failure, response);
        }
        if (e instanceof ResourceAccessException) {
            LogEvent.warn(log, "catedra.llamada.sin_respuesta", "operacion", operationCode,
                    "motivo", e.getClass().getSimpleName(), "resultado", "se_desconoce_si_se_aplico");
        } else {
            LogEvent.warn(log, "catedra.respuesta.ilegible", "operacion", operationCode,
                    "resultado", "se_desconoce_si_se_aplico");
        }
        return new CatedraUnavailableException("No se sabe qué pasó al " + operation, e);
    }

    private static CatedraFailure failureOf(String code) {
        if (code == null) {
            return CatedraFailure.UNEXPECTED;
        }
        try {
            return CatedraFailure.valueOf(code);
        } catch (IllegalArgumentException unknown) {
            return CatedraFailure.UNEXPECTED;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private record CreateHoldJson(long professionalId, String date, String startTime, String externalPatientId) {
    }

    private record ConfirmHoldJson(String externalPatientId, String patientFirstName, String patientLastName) {

        @Override
        public String toString() {
            return "ConfirmHoldJson[...]"; // los nombres del paciente no llegan a un log por accidente
        }
    }

    /** Parte del JSON de la cátedra que cronos usa; los campos que no conoce se ignoran. */
    record HoldCreatedJson(String holdId, String reservationProcessId, String status, Instant expiresAt) {
    }

    record HoldConfirmedJson(String reservationProcessId, String status, Instant expiresAt) {
    }
}
