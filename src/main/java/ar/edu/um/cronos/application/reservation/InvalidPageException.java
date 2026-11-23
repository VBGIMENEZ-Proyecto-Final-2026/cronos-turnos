package ar.edu.um.cronos.application.reservation;

/** La página o el tamaño pedidos no son válidos. Nombra el parámetro, no repite el valor. */
public class InvalidPageException extends RuntimeException {

    private final String parameter;

    public InvalidPageException(String parameter) {
        super("Parámetro de paginación inválido");
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }
}
