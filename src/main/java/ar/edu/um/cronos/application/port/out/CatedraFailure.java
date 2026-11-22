package ar.edu.um.cronos.application.port.out;

/**
 * Por qué la cátedra rechazó una operación sobre un hold, según el {@code code} de su respuesta (referencia de
 * integración, 13.1). Se decide siempre por {@code status} y {@code code}, nunca por el texto de {@code detail}.
 */
public enum CatedraFailure {
    SLOT_ALREADY_HELD,
    SLOT_ALREADY_RESERVED,
    INVALID_SLOT,
    PROFESSIONAL_DISABLED,
    PROFESSIONAL_NOT_FOUND,
    HOLD_EXPIRED,
    HOLD_NOT_FOUND,
    /** El hold es de otra integración técnica: no debería pasar, es un error de configuración. */
    HOLD_OWNER_MISMATCH,
    /** El paciente no coincide con el del hold: no debería pasar, es un error de cronos. */
    EXTERNAL_PATIENT_MISMATCH,
    /** El proceso ya estaba confirmado inicialmente: repetir la confirmación no cambia nada. */
    RESERVATION_PROCESS_ALREADY_CONFIRMED,
    // Cancelar (referencia 12 y 13.1)
    RESERVATION_NOT_FOUND,
    RESERVATION_OWNER_MISMATCH,
    RESERVATION_NOT_CANCELLABLE,
    /** La cátedra rechazó el cuerpo (400): un error de cronos. */
    VALIDATION_ERROR,
    /** Cualquier otro rechazo que cronos no sabe interpretar. */
    UNEXPECTED;

    /** Los rechazos que significan que el hold ya no existe o venció. */
    public boolean holdIsGone() {
        return this == HOLD_EXPIRED || this == HOLD_NOT_FOUND;
    }
}
