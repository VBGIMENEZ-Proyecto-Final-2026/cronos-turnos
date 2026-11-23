package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Event;
import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Malformed;
import ar.edu.um.cronos.adapters.in.kafka.ParsedMessage.Skipped;
import ar.edu.um.cronos.domain.ReservationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Interpretar los mensajes de la cátedra: los reales del topic (con los identificadores cambiados), los ejemplos de la
 * referencia 15.4 a 15.10, lo que se tolera, lo que se rechaza y que nunca lanza una excepción.
 */
class ReservationEventParserTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private final ReservationEventParser parser = new ReservationEventParser(MAPPER);

    // ---- ayudas ---------------------------------------------------------------------------------------------

    /** Un mensaje mínimo válido del tipo dado, más los campos extra (ya en JSON, sin llaves). */
    private static String msg(String type, String extra) {
        return "{\"eventId\":\"ev-1\",\"eventType\":\"" + type + "\",\"reservationProcessId\":\"process-1\","
                + "\"holdId\":\"hold-1\",\"schemaVersion\":1" + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    private static ReservationEvent event(ParsedMessage parsed) {
        assertThat(parsed).isInstanceOf(Event.class);
        return ((Event) parsed).event();
    }

    private static List<String> mensajesReales() throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("src/test/resources/kafka/acciones-reales.jsonl"))) {
            JsonNode value = MAPPER.readTree(line).get("value");
            out.add(MAPPER.writeValueAsString(value));
        }
        return out;
    }

    // ---- los mensajes reales ---------------------------------------------------------------------------------

    @Test
    void losSeisMensajesRealesDelTopicSeInterpretan() throws IOException {
        var reales = mensajesReales();
        assertThat(reales).hasSize(6);

        for (int i = 0; i < reales.size(); i++) {
            var evento = event(parser.parse(reales.get(i)));
            if (i % 2 == 0) {
                assertThat(evento).isInstanceOf(ReservationEvent.InformationRequested.class);
                assertThat(evento.eventId()).startsWith("request-event-");
            } else {
                assertThat(evento).isInstanceOf(ReservationEvent.ProcessExpired.class);
                assertThat(evento.eventId()).startsWith("process-event-");
            }
            assertThat(evento.reservationProcessId()).startsWith("process-");
            assertThat(evento.holdId()).startsWith("hold-");
        }
        // El Requested y el Expired de un mismo proceso hablan del mismo proceso y el mismo hold.
        assertThat(event(parser.parse(reales.get(0))).reservationProcessId())
                .isEqualTo(event(parser.parse(reales.get(1))).reservationProcessId());
        assertThat(event(parser.parse(reales.get(0))).holdId()).isEqualTo(event(parser.parse(reales.get(1))).holdId());
    }

    @Test
    void lasFechasConNanosegundosDeLaCatedraRealNoLosRompen() throws IOException {
        // La cátedra manda 2026-11-21T17:02:25.905856025Z. El intérprete no lee las fechas, así que no puede fallar por ellas.
        var reales = mensajesReales();
        assertThat(reales).anyMatch(m -> m.matches(".*\\d{4}-\\d\\d-\\d\\dT[\\d:]+\\.\\d{9}Z.*"));
        assertThat(reales).allSatisfy(m -> assertThat(parser.parse(m)).isInstanceOf(Event.class));
    }

    @Test
    void elExpiredRealTraeElGroupIdYNoSeLeNota() throws IOException {
        // El groupId contiene datos del alumno: no se lee, no se guarda y no aparece en el evento.
        var expired = mensajesReales().get(1);
        assertThat(expired).contains("groupId");

        assertThat(event(parser.parse(expired)).toString()).doesNotContain("grupo-de-prueba");
    }

    // ---- los ejemplos de la referencia ------------------------------------------------------------------------

    @Test
    void cadaTipoDeLaReferenciaSeInterpretaConSusCampos() {
        assertThat(event(parser.parse("""
                {"eventId":"request-event-8a31","eventType":"AdditionalInformationRequested",
                 "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requiredField":"PHONE_NUMBER",
                 "message":"Ingrese un numero de telefono para continuar.","expiresAt":"2026-09-10T14:44:50Z",
                 "occurredAt":"2026-09-10T14:35:10Z","schemaVersion":1}""")))
                .isEqualTo(new ReservationEvent.InformationRequested("request-event-8a31", "process-a92b47d0",
                        "hold-6c0c2f41"));

        assertThat(event(parser.parse("""
                {"eventId":"rejection-event-7d11","eventType":"AdditionalInformationRejected",
                 "groupId":"proyecto-juan-perez","reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41",
                 "requestEventId":"request-event-8a31","reason":"INVALID_PHONE_NUMBER",
                 "message":"El telefono ingresado no es valido.","expiresAt":"2026-09-10T14:44:50Z",
                 "occurredAt":"2026-09-10T14:36:01Z","schemaVersion":1}""")))
                .isEqualTo(new ReservationEvent.InformationRejected("rejection-event-7d11", "process-a92b47d0",
                        "hold-6c0c2f41", "INVALID_PHONE_NUMBER"));

        assertThat(event(parser.parse("""
                {"eventId":"confirmation-event-7d10","eventType":"AppointmentConfirmed","groupId":"proyecto-juan-perez",
                 "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
                 "reservationId":8251,"professionalId":15,"date":"2026-09-14","startTime":"09:30","status":"CONFIRMED",
                 "occurredAt":"2026-09-10T14:36:01Z","schemaVersion":1}""")))
                .isEqualTo(new ReservationEvent.AppointmentConfirmed("confirmation-event-7d10", "process-a92b47d0",
                        "hold-6c0c2f41", 8251));

        assertThat(event(parser.parse("""
                {"eventId":"process-event-7d12","eventType":"AppointmentProcessExpired","groupId":"proyecto-juan-perez",
                 "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
                 "reason":"HOLD_EXPIRED","occurredAt":"2026-09-10T14:44:52Z","schemaVersion":1}""")))
                .isEqualTo(new ReservationEvent.ProcessExpired("process-event-7d12", "process-a92b47d0",
                        "hold-6c0c2f41"));

        assertThat(event(parser.parse("""
                {"eventId":"process-event-7d13","eventType":"AppointmentProcessInvalid","groupId":"proyecto-juan-perez",
                 "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
                 "reason":"REQUEST_EVENT_MISMATCH","occurredAt":"2026-09-10T14:36:01Z","schemaVersion":1}""")))
                .isEqualTo(new ReservationEvent.ProcessInvalid("process-event-7d13", "process-a92b47d0",
                        "hold-6c0c2f41", "REQUEST_EVENT_MISMATCH"));

        assertThat(event(parser.parse("""
                {"eventId":"cancellation-event-7d14","eventType":"AppointmentCancelled","groupId":"proyecto-juan-perez",
                 "reservationProcessId":"process-a92b47d0","holdId":"hold-6c0c2f41","requestEventId":"request-event-8a31",
                 "reservationId":8251,"professionalId":15,"date":"2026-09-14","startTime":"09:30","status":"CANCELLED",
                 "reason":null,"occurredAt":"2026-09-11T15:10:00Z","schemaVersion":1}""")))
                .isEqualTo(new ReservationEvent.AppointmentCancelled("cancellation-event-7d14", "process-a92b47d0",
                        "hold-6c0c2f41"));
    }

    // ---- lo que se tolera -------------------------------------------------------------------------------------

    @Test
    void losCamposQueNoConoceSeIgnoranIncluidosLosAnidados() {
        var evento = event(parser.parse(msg("AppointmentProcessExpired",
                "\"campoNuevo\":{\"a\":[1,2,{\"b\":null}]},\"otro\":true,\"phoneNumber\":\"+5492615551234\"")));

        assertThat(evento).isEqualTo(new ReservationEvent.ProcessExpired("ev-1", "process-1", "hold-1"));
        assertThat(evento.toString()).doesNotContain("5492615551234");
    }

    @Test
    void elHoldIdEsOpcionalYElMotivoTambien() {
        var sinHold = "{\"eventId\":\"ev-1\",\"eventType\":\"AppointmentProcessInvalid\","
                + "\"reservationProcessId\":\"process-1\",\"schemaVersion\":1}";

        assertThat(event(parser.parse(sinHold))).isEqualTo(
                new ReservationEvent.ProcessInvalid("ev-1", "process-1", null, null));
        assertThat(event(parser.parse(msg("AdditionalInformationRejected", "\"reason\":null"))))
                .isEqualTo(new ReservationEvent.InformationRejected("ev-1", "process-1", "hold-1", null));
    }

    @Test
    void unTipoQueCronosNoUsaSeSaltaSinSerUnError() {
        assertThat(parser.parse("{\"eventId\":\"e\",\"eventType\":\"CatalogUpdated\",\"newVersion\":7,\"schemaVersion\":1}"))
                .isEqualTo(new Skipped("CatalogUpdated"));
        // Aunque sea de una versión de esquema nueva: no es un tipo que cronos use.
        assertThat(parser.parse("{\"eventType\":\"TipoFuturo\",\"schemaVersion\":9}")).isEqualTo(new Skipped("TipoFuturo"));
        // Y un tipo larguísimo no llega entero al log.
        assertThat(parser.parse("{\"eventType\":\"" + "X".repeat(500) + "\"}"))
                .isEqualTo(new Skipped("X".repeat(60)));
    }

    // ---- lo que se rechaza ------------------------------------------------------------------------------------

    static Stream<Arguments> rotos() {
        String largo = "a".repeat(301);
        return Stream.of(
                Arguments.of("null", null, "mensaje_vacio", null),
                Arguments.of("vacío", "", "mensaje_vacio", null),
                Arguments.of("solo espacios", "   \n ", "mensaje_vacio", null),
                Arguments.of("no es JSON", "esto no es json", "json_ilegible", null),
                Arguments.of("JSON cortado", "{\"eventId\":\"e\",\"eventType\":", "json_ilegible", null),
                Arguments.of("un arreglo", "[1,2,3]", "no_es_un_objeto", null),
                Arguments.of("un número", "42", "no_es_un_objeto", null),
                Arguments.of("un texto", "\"hola\"", "no_es_un_objeto", null),
                Arguments.of("la palabra null", "null", "no_es_un_objeto", null),
                Arguments.of("sin eventType", "{\"eventId\":\"e\",\"schemaVersion\":1}", "falta_campo", "eventType"),
                Arguments.of("eventType nulo", "{\"eventType\":null}", "falta_campo", "eventType"),
                Arguments.of("eventType numérico", "{\"eventType\":5}", "campo_invalido", "eventType"),
                Arguments.of("eventType vacío", "{\"eventType\":\" \"}", "campo_invalido", "eventType"),
                Arguments.of("sin schemaVersion", "{\"eventType\":\"AppointmentCancelled\",\"eventId\":\"e\","
                        + "\"reservationProcessId\":\"p\"}", "falta_campo", "schemaVersion"),
                Arguments.of("schemaVersion 2", msg("AppointmentCancelled", "").replace("\"schemaVersion\":1",
                        "\"schemaVersion\":2"), "schema_no_soportado", "schemaVersion"),
                Arguments.of("schemaVersion como texto", msg("AppointmentCancelled", "").replace(
                        "\"schemaVersion\":1", "\"schemaVersion\":\"1\""), "schema_no_soportado", "schemaVersion"),
                Arguments.of("schemaVersion decimal", msg("AppointmentCancelled", "").replace("\"schemaVersion\":1",
                        "\"schemaVersion\":1.5"), "schema_no_soportado", "schemaVersion"),
                Arguments.of("sin eventId", "{\"eventType\":\"AppointmentCancelled\",\"reservationProcessId\":\"p\","
                        + "\"schemaVersion\":1}", "falta_campo", "eventId"),
                Arguments.of("eventId numérico", msg("AppointmentCancelled", "").replace("\"ev-1\"", "7"),
                        "campo_invalido", "eventId"),
                Arguments.of("eventId vacío", msg("AppointmentCancelled", "").replace("\"ev-1\"", "\"\""),
                        "campo_invalido", "eventId"),
                Arguments.of("eventId de 301 caracteres", msg("AppointmentCancelled", "").replace("ev-1", largo),
                        "campo_invalido", "eventId"),
                Arguments.of("eventId con salto de línea", msg("AppointmentCancelled", "").replace("ev-1", "a\\nb"),
                        "campo_invalido", "eventId"),
                Arguments.of("eventId con espacio", msg("AppointmentCancelled", "").replace("ev-1", "a b"),
                        "campo_invalido", "eventId"),
                Arguments.of("eventId con tildes", msg("AppointmentCancelled", "").replace("ev-1", "evént"),
                        "campo_invalido", "eventId"),
                Arguments.of("sin proceso", "{\"eventId\":\"e\",\"eventType\":\"AppointmentCancelled\","
                        + "\"schemaVersion\":1}", "falta_campo", "reservationProcessId"),
                Arguments.of("proceso de 101 caracteres", msg("AppointmentCancelled", "").replace("process-1",
                        "p".repeat(101)), "campo_invalido", "reservationProcessId"),
                Arguments.of("holdId numérico", msg("AppointmentCancelled", "").replace("\"hold-1\"", "3"),
                        "campo_invalido", "holdId"),
                Arguments.of("holdId de 101 caracteres", msg("AppointmentCancelled", "").replace("hold-1",
                        "h".repeat(101)), "campo_invalido", "holdId"),
                Arguments.of("Confirmed sin reservationId", msg("AppointmentConfirmed", ""), "falta_campo",
                        "reservationId"),
                Arguments.of("reservationId como texto", msg("AppointmentConfirmed", "\"reservationId\":\"8251\""),
                        "campo_invalido", "reservationId"),
                Arguments.of("reservationId cero", msg("AppointmentConfirmed", "\"reservationId\":0"),
                        "campo_invalido", "reservationId"),
                Arguments.of("reservationId negativo", msg("AppointmentConfirmed", "\"reservationId\":-5"),
                        "campo_invalido", "reservationId"),
                Arguments.of("reservationId decimal", msg("AppointmentConfirmed", "\"reservationId\":1.5"),
                        "campo_invalido", "reservationId"),
                Arguments.of("reservationId enorme", msg("AppointmentConfirmed",
                        "\"reservationId\":123456789012345678901234567890"), "campo_invalido", "reservationId"),
                Arguments.of("reason numérico", msg("AdditionalInformationRejected", "\"reason\":5"),
                        "campo_invalido", "reason"),
                Arguments.of("reason de 201 caracteres", msg("AppointmentProcessInvalid",
                        "\"reason\":\"" + "R".repeat(201) + "\""), "campo_invalido", "reason"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rotos")
    void unMensajeQueNoSePuedeUsarVuelveComoMalformadoConSuMotivo(String caso, String mensaje, String motivo,
                                                                  String campo) {
        assertThat(parser.parse(mensaje)).as(caso).isEqualTo(new Malformed(motivo, campo));
    }

    @Test
    void unMensajeDemasiadoGrandeSeRechazaSinInterpretarlo() {
        String grande = msg("AppointmentCancelled", "\"relleno\":\"" + "x".repeat(ReservationEventParser.MAX_MESSAGE_CHARS) + "\"");

        assertThat(parser.parse(grande)).isEqualTo(new Malformed("mensaje_demasiado_grande", null));
    }

    @Test
    void unMensajeRotoNoLlevaSuContenidoNiElDeLosCamposQueNoPidio() {
        String conTelefono = "{\"eventType\":\"AppointmentConfirmed\",\"schemaVersion\":1,\"eventId\":\"e\","
                + "\"reservationProcessId\":\"p\",\"phoneNumber\":\"+5492615551234\",\"reservationId\":\"mal\"";

        // JSON cortado y también un campo inválido con un teléfono al lado: el resultado nunca los repite.
        assertThat(parser.parse(conTelefono).toString()).doesNotContain("5492615551234").doesNotContain("mal");
        assertThat(parser.parse(conTelefono + "}").toString()).doesNotContain("5492615551234");
    }

    // ---- nunca lanza -----------------------------------------------------------------------------------------

    @Test
    void nuncaLanzaUnaExcepcionAunqueElMensajeEsteCortadoEnCualquierPunto() throws IOException {
        for (String real : mensajesReales()) {
            for (int corte = 0; corte <= real.length(); corte++) {
                var resultado = parser.parse(real.substring(0, corte));
                if (corte < real.length()) {
                    assertThat(resultado).as("cortado en %d", corte).isInstanceOf(Malformed.class);
                } else {
                    assertThat(resultado).isInstanceOf(Event.class);
                }
            }
        }
    }

    @Test
    void nuncaLanzaUnaExcepcionConMensajesAlteradosAlAzar() throws IOException {
        var azar = new Random(42);
        var reales = mensajesReales();
        int eventos = 0, malformados = 0, saltados = 0;
        for (int i = 0; i < 3000; i++) {
            char[] texto = reales.get(azar.nextInt(reales.size())).toCharArray();
            for (int cambios = 1 + azar.nextInt(4); cambios > 0; cambios--) {
                texto[azar.nextInt(texto.length)] = (char) azar.nextInt(0x2FF);
            }
            var resultado = parser.parse(new String(texto));
            switch (resultado) {
                case Event e -> eventos++;
                case Malformed m -> malformados++;
                case Skipped s -> saltados++;
            }
        }
        // El ejercicio tiene que mirar de verdad los tres resultados, no pasar en vacío.
        assertThat(eventos).isPositive();
        assertThat(malformados).isPositive();
        assertThat(eventos + malformados + saltados).isEqualTo(3000);
    }

    @Test
    void nuncaLanzaConUnaAnidacionProfundaNiCaracteresDeControl() {
        assertThat(parser.parse("[".repeat(5000))).isInstanceOf(Malformed.class);
        assertThat(parser.parse("{\"a\":".repeat(3000))).isInstanceOf(Malformed.class);
        assertThat(parser.parse("\u0000\u0001\u0002")).isInstanceOf(Malformed.class);
        assertThat(parser.parse("{\"eventType\":\"AppointmentCancelled\"\u0000}")).isInstanceOf(Malformed.class);
    }
}
