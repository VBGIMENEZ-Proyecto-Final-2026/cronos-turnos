package ar.edu.um.cronos.application.reservation;

/** Ya se mandó un teléfono y la cátedra todavía no respondió: no se manda otro hasta que rechace o pida de nuevo. */
public class PhoneAlreadySubmittedException extends RuntimeException {

    public PhoneAlreadySubmittedException() {
        super("Ya se envió un teléfono y la cátedra todavía no respondió");
    }
}
