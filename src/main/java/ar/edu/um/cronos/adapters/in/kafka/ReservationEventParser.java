package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Event;
import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Malformed;
import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Skipped;
import ar.edu.um.cronos.domain.ReservationEvent;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.regex.Pattern;

/**
 * Convierte un mensaje del topic de acciones (JSON) en un {@link ReservationEvent} (referencia 15.4 a 15.10).
 *
 * <p><b>Qué exige:</b> un objeto con {@code eventType} y {@code schemaVersion} 1 (el número, no el texto) y, según el
 * tipo, {@code eventId} y {@code reservationProcessId} (siempre), {@code reservationId} (en {@code AppointmentConfirmed}).
 * {@code holdId} y {@code reason} son opcionales, pero si vienen tienen que ser texto.
 *
 * <p><b>Qué tolera:</b> campos que no conoce (la cátedra puede agregar), un {@code eventType} que no usa
 * ({@link ParsedMessage.Skipped}) y fechas con la precisión que sea: <b>no lee {@code expiresAt} ni {@code occurredAt}</b>,
 * ningún caso de uso los necesita, así que los nanosegundos que manda la cátedra real no pueden romperlo.
 *
 * <p><b>Qué acota</b> (el mensaje viene de afuera y sus valores terminan en una base y en un log): el mensaje entero a
 * {@value #MAX_MESSAGE_CHARS} caracteres y los identificadores a lo que cabe en las columnas de la base, solo con letras,
 * números, punto, guion, guion bajo y dos puntos. Un {@code eventId} de 300 caracteres o con un salto de línea se rechaza
 * acá, antes de que un {@code INSERT} falle y trabe el consumo.
 *
 * <p><b>Nunca lanza:</b> todo lo que no se entiende vuelve como {@link ParsedMessage.Malformed}, sin el contenido del
 * mensaje (puede traer el teléfono o un nombre en un campo que cronos no pidió).
 */
public class ReservationEventParser {

    static final int MAX_MESSAGE_CHARS = 64 * 1024;
    private static final int MAX_EVENT_ID = 200;
    private static final int MAX_PROCESS_ID = 100;
    private static final int MAX_HOLD_ID = 100;
    private static final int MAX_REASON = 200;
    private static final int MAX_EVENT_TYPE = 60;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9._:-]+");

    private final JsonMapper mapper;

    public ReservationEventParser(JsonMapper mapper) {
        this.mapper = mapper;
    }

    public ParsedMessage parse(String message) {
        if (message == null || message.isBlank()) {
            return new Malformed("mensaje_vacio", null);
        }
        if (message.length() > MAX_MESSAGE_CHARS) {
            return new Malformed("mensaje_demasiado_grande", null);
        }
        JsonNode root;
        try {
            root = mapper.readTree(message);
        } catch (JacksonException e) {
            return new Malformed("json_ilegible", null);
        }
        if (root == null || !root.isObject()) {
            return new Malformed("no_es_un_objeto", null);
        }

        JsonNode type = root.get("eventType");
        if (type == null || type.isNull()) {
            return new Malformed("falta_campo", "eventType");
        }
        if (!type.isString() || type.asString().isBlank()) {
            return new Malformed("campo_invalido", "eventType");
        }
        String eventType = type.asString();
        if (!isKnown(eventType)) {
            return new Skipped(eventType.length() > MAX_EVENT_TYPE ? eventType.substring(0, MAX_EVENT_TYPE) : eventType);
        }

        JsonNode schema = root.get("schemaVersion");
        if (schema == null || schema.isNull()) {
            return new Malformed("falta_campo", "schemaVersion");
        }
        if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 1) {
            return new Malformed("schema_no_soportado", "schemaVersion");
        }

        Field eventId = id(root, "eventId", MAX_EVENT_ID, true);
        if (eventId.problem != null) {
            return eventId.problem;
        }
        Field processId = id(root, "reservationProcessId", MAX_PROCESS_ID, true);
        if (processId.problem != null) {
            return processId.problem;
        }
        Field holdId = id(root, "holdId", MAX_HOLD_ID, false);
        if (holdId.problem != null) {
            return holdId.problem;
        }

        String e = eventId.value, p = processId.value, h = holdId.value;
        return switch (eventType) {
            case "AdditionalInformationRequested" -> new Event(new ReservationEvent.InformationRequested(e, p, h));
            case "AdditionalInformationRejected" -> withReason(root, reason ->
                    new ReservationEvent.InformationRejected(e, p, h, reason));
            case "AppointmentConfirmed" -> reservationId(root, id ->
                    new ReservationEvent.AppointmentConfirmed(e, p, h, id));
            case "AppointmentProcessExpired" -> new Event(new ReservationEvent.ProcessExpired(e, p, h));
            case "AppointmentProcessInvalid" -> withReason(root, reason ->
                    new ReservationEvent.ProcessInvalid(e, p, h, reason));
            case "AppointmentCancelled" -> new Event(new ReservationEvent.AppointmentCancelled(e, p, h));
            default -> throw new IllegalStateException("tipo conocido sin interpretar: " + eventType);
        };
    }

    private static boolean isKnown(String eventType) {
        return switch (eventType) {
            case "AdditionalInformationRequested", "AdditionalInformationRejected", "AppointmentConfirmed",
                 "AppointmentProcessExpired", "AppointmentProcessInvalid", "AppointmentCancelled" -> true;
            default -> false;
        };
    }

    /** Un identificador: texto no vacío, acotado y con caracteres seguros. Si es opcional y falta, es {@code null}. */
    private static Field id(JsonNode root, String name, int max, boolean required) {
        JsonNode node = root.get(name);
        if (node == null || node.isNull()) {
            return required ? Field.bad(new Malformed("falta_campo", name)) : Field.of(null);
        }
        if (!node.isString()) {
            return Field.bad(new Malformed("campo_invalido", name));
        }
        String value = node.asString();
        if (value.isEmpty() || value.length() > max || !ID.matcher(value).matches()) {
            return Field.bad(new Malformed("campo_invalido", name));
        }
        return Field.of(value);
    }

    /** El motivo es opcional; si viene tiene que ser texto y no pasar de {@value #MAX_REASON} (el dominio lo filtra). */
    private ParsedMessage withReason(JsonNode root, java.util.function.Function<String, ReservationEvent> build) {
        JsonNode node = root.get("reason");
        String reason = null;
        if (node != null && !node.isNull()) {
            if (!node.isString() || node.asString().length() > MAX_REASON) {
                return new Malformed("campo_invalido", "reason");
            }
            reason = node.asString();
        }
        return new Event(build.apply(reason));
    }

    private ParsedMessage reservationId(JsonNode root, java.util.function.LongFunction<ReservationEvent> build) {
        JsonNode node = root.get("reservationId");
        if (node == null || node.isNull()) {
            return new Malformed("falta_campo", "reservationId");
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() <= 0) {
            return new Malformed("campo_invalido", "reservationId");
        }
        return new Event(build.apply(node.longValue()));
    }

    /** Un valor leído o el motivo por el que no se pudo leer. */
    private record Field(String value, Malformed problem) {
        static Field of(String value) {
            return new Field(value, null);
        }

        static Field bad(Malformed problem) {
            return new Field(null, problem);
        }
    }
}
