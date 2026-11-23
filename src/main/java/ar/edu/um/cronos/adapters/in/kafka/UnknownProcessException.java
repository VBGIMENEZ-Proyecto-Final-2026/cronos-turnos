package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.domain.ReservationEvent;

/**
 * El evento es de un proceso que cronos no tiene. Se lanza para que el manejador de errores del contenedor lo reintente de
 * forma acotada (puede ser un evento que ganó la carrera contra el guardado del proceso) y, si sigue sin existir, lo
 * estacione. Lleva el evento pero no lo imprime: el mensaje es fijo.
 */
public class UnknownProcessException extends RuntimeException {

    private final transient ReservationEvent event;

    public UnknownProcessException(ReservationEvent event) {
        super("El proceso del evento no existe en cronos");
        this.event = event;
    }

    public ReservationEvent event() {
        return event;
    }
}
