package ar.edu.um.cronos.domain;

/** El número de teléfono no tiene un formato que la cátedra acepte. El mensaje es fijo: nunca repite el número recibido. */
public class InvalidPhoneNumberException extends RuntimeException {

    public InvalidPhoneNumberException() {
        super("El número de teléfono no tiene un formato válido");
    }
}
