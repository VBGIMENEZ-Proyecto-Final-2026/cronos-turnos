package ar.edu.um.cronos.application.port.out;

import java.time.Instant;

/**
 * Los eventos que no se pudieron aplicar porque el proceso no existe en esta base. Se estacionan para diagnosticarlos
 * en lugar de trabar el consumo o perderse en silencio. Guarda solo identificadores, nunca datos personales.
 */
public interface UnmatchedEvents {

    /** @return {@code true} si se estacionó; {@code false} si ese mismo evento ya estaba estacionado */
    boolean park(String eventId, String eventType, String reservationProcessId, String reason, Instant at);
}
