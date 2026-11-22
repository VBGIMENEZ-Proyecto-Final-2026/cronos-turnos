package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.domain.ReservationEvent;

/**
 * Qué es un mensaje del topic de acciones una vez interpretado. Interpretar nunca lanza una excepción: un mensaje que no
 * se entiende es un resultado más, y quien lo consume lo descarta sin trabar la partición (referencia 15.1).
 */
public sealed interface ParsedMessage {

    /** Un evento de reserva válido, listo para aplicar. */
    record Event(ReservationEvent event) implements ParsedMessage {
    }

    /**
     * Un mensaje bien formado de un tipo que cronos no usa. La referencia pide ignorar lo compatible que no se conoce.
     *
     * @param eventType el tipo tal como vino, acotado a 60 caracteres
     */
    record Skipped(String eventType) implements ParsedMessage {
    }

    /**
     * Un mensaje que no se puede usar: JSON roto, sin un campo obligatorio, con un tipo equivocado o con una versión de
     * esquema que cronos no conoce. No lleva el contenido del mensaje, solo qué falló.
     *
     * @param reason un código estable: {@code mensaje_vacio}, {@code mensaje_demasiado_grande}, {@code json_ilegible},
     *               {@code no_es_un_objeto}, {@code falta_campo}, {@code campo_invalido} o {@code schema_no_soportado}
     * @param field  el nombre del campo que falló, si lo hay (siempre uno de los nombres que cronos espera)
     */
    record Malformed(String reason, String field) implements ParsedMessage {
    }
}
