package ar.edu.um.cronos.application.reservation;

/** El turno pedido no existe en la agenda vigente del profesional, o ya empezó. */
public class InvalidSlotException extends RuntimeException {

    public InvalidSlotException() {
        super("El turno no existe en la agenda del profesional o ya empezó");
    }
}
