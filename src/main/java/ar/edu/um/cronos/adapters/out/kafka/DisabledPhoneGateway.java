package ar.edu.um.cronos.adapters.out.kafka;

import ar.edu.um.cronos.application.port.out.PhoneGateway;
import ar.edu.um.cronos.application.port.out.PhoneSubmission;
import ar.edu.um.cronos.application.port.out.PhoneUnavailableException;

/** Con el envío apagado ({@code CRONOS_PHONE_PRODUCER_ENABLED=false}) el endpoint del teléfono responde {@code 503}. */
public class DisabledPhoneGateway implements PhoneGateway {

    @Override
    public void submit(PhoneSubmission submission) {
        throw new PhoneUnavailableException(new IllegalStateException("el envío del teléfono está apagado"));
    }
}
