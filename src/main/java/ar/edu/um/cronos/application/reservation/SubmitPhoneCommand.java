package ar.edu.um.cronos.application.reservation;

/**
 * @param login         el usuario del token (el dueño que se verifica; nunca el que diga el cuerpo)
 * @param reservationId el id local del proceso
 * @param phoneNumber   lo que escribió el usuario, sin limpiar
 */
public record SubmitPhoneCommand(String login, long reservationId, String phoneNumber) {

    /** Sin el teléfono: un dato personal no tiene por qué llegar a un log por accidente. */
    @Override
    public String toString() {
        return "SubmitPhoneCommand[reservationId=" + reservationId + ", phoneNumber=***]";
    }
}
