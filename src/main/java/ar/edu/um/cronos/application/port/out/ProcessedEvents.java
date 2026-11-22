package ar.edu.um.cronos.application.port.out;

import java.time.Instant;

/**
 * Los eventos de la cátedra que ya se aplicaron (referencia 15.1 y 18.1: la entrega es "al menos una vez"). Se usa
 * <b>dentro de la misma transacción</b> que cambia el estado del proceso, así que un evento queda marcado si y solo si
 * su efecto se guardó.
 */
public interface ProcessedEvents {

    /**
     * Marca el evento como procesado.
     *
     * @return {@code true} si es la primera vez; {@code false} si ya estaba marcado (otro lo aplicó antes, incluso
     *         otro hilo a la vez: quien llega segundo espera a que el primero termine y recibe {@code false})
     */
    boolean markProcessed(String eventId, String eventType, String reservationProcessId, Instant at);
}
