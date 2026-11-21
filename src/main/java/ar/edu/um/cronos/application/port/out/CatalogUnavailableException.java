package ar.edu.um.cronos.application.port.out;

/**
 * El catálogo (atlas) no se puede consultar: error de red, timeout, {@code 5xx} o una respuesta que cronos no puede
 * usar. Sin catálogo no se puede iniciar una operación que necesite validar información vigente (enunciado 8).
 */
public class CatalogUnavailableException extends RuntimeException {

    public CatalogUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
