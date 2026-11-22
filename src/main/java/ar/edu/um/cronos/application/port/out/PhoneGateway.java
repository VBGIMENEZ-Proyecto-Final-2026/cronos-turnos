package ar.edu.um.cronos.application.port.out;

/**
 * Manda el teléfono a la cátedra por Kafka (topic del teléfono, referencia 15.5). Lo implementa el adaptador de Kafka.
 */
public interface PhoneGateway {

    /**
     * Publica el mensaje y espera la confirmación del broker. Sin reintentos ilimitados: si no se pudo confirmar a tiempo,
     * falla y el usuario puede volver a intentarlo.
     *
     * @throws PhoneUnavailableException si no se pudo publicar (Kafka no responde, timeout o está apagado el envío)
     */
    void submit(PhoneSubmission submission);
}
