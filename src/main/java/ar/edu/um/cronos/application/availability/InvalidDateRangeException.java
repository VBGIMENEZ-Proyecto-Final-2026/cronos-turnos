package ar.edu.um.cronos.application.availability;

/** El rango de fechas pedido no es válido: {@code from} posterior a {@code to}, o más largo que el máximo. */
public class InvalidDateRangeException extends RuntimeException {

    public InvalidDateRangeException(String message) {
        super(message);
    }
}
