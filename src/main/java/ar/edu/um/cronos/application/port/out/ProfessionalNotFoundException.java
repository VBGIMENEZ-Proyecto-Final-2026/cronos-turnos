package ar.edu.um.cronos.application.port.out;

/** El catálogo no conoce al profesional pedido. */
public class ProfessionalNotFoundException extends RuntimeException {

    public ProfessionalNotFoundException(long professionalId) {
        super("No existe el profesional " + professionalId);
    }
}
