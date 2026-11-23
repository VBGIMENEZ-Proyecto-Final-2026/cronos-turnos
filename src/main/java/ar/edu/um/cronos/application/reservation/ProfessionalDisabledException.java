package ar.edu.um.cronos.application.reservation;

/** El profesional está deshabilitado en el catálogo: no se le pueden reservar turnos. */
public class ProfessionalDisabledException extends RuntimeException {

    public ProfessionalDisabledException() {
        super("El profesional está deshabilitado");
    }
}
