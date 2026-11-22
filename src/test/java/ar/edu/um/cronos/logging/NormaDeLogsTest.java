package ar.edu.um.cronos.logging;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hace cumplir la norma de logs (ADR 0006) sobre el código fuente: cada evento tiene un código con la forma
 * {@code dominio.accion[.detalle]} y ningún dato que la norma prohíbe se pasa como valor.
 */
class NormaDeLogsTest {

    private static final Path MAIN = Path.of("src/main/java");
    /** {@code LogEvent.info(log, "codigo", ...);} hasta el punto y coma que cierra la sentencia. */
    private static final Pattern CALL = Pattern.compile(
            "LogEvent\\.(?:info|warn|error)\\(\\s*log,\\s*(?:\"([^\"]*)\")?([^;]*);");
    private static final Pattern CODE = Pattern.compile("[a-z][a-z_]*(\\.[a-z][a-z_]*){1,3}");
    /** Lo que nunca va a un log: contraseñas, teléfono, nombres del paciente o del usuario, tokens y groupId. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i)\\b(password\\w*|phone\\w*|telefono|patientFirstName|patientLastName|firstName|lastName|"
                    + "userToken|idToken|token|groupId|login)\\b");

    private record Call(Path file, String code, String arguments) {
    }

    private static List<Call> calls() throws IOException {
        List<Call> calls = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                if (file.endsWith("LogEvent.java")) {
                    continue; // la propia definición no es un uso
                }
                Matcher m = CALL.matcher(Files.readString(file));
                while (m.find()) {
                    calls.add(new Call(file, m.group(1), m.group(2)));
                }
            }
        }
        return calls;
    }

    @Test
    void elEscaneoEncuentraLosEventosDelCodigo() throws IOException {
        // Sin esto, un cambio en la expresión haría que las demás pruebas pasen sin mirar nada.
        assertThat(calls()).hasSizeGreaterThan(25);
    }

    @Test
    void todoCodigoDeEventoTieneLaFormaDeLaNorma() throws IOException {
        assertThat(calls()).allSatisfy(call -> assertThat(call.code())
                .as("código de evento en %s", call.file()).isNotNull().matches(CODE));
    }

    @Test
    void ningunDatoProhibidoSePasaComoValor() throws IOException {
        for (Call call : calls()) {
            Matcher forbidden = FORBIDDEN.matcher(call.arguments());
            assertThat(forbidden.find())
                    .as("%s (%s) pasa '%s', que la norma de logs prohíbe",
                            call.file(), call.code(), forbidden.find(0) ? forbidden.group() : "")
                    .isFalse();
        }
    }
}
