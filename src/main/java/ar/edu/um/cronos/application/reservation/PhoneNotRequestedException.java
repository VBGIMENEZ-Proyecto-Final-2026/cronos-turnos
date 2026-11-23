package ar.edu.um.cronos.application.reservation;

/**
 * Todavía no llegó el pedido de teléfono de la cátedra ({@code AdditionalInformationRequested}): el proceso está en
 * {@code HELD} o recién pasó a esperar el teléfono y el evento no se recibió. Se puede reintentar en unos segundos.
 */
public class PhoneNotRequestedException extends RuntimeException {

    public PhoneNotRequestedException() {
        super("La cátedra todavía no pidió el teléfono de esta reserva");
    }
}
