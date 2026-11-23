package ar.edu.um.cronos.logging;

import org.slf4j.Logger;

/**
 * Escribe los eventos del log con la forma fija de la norma de logs (ADR 0006):
 *
 * <pre>evento=reserva.hold.creado proceso=process-ab12 usuario=7 resultado=ok ms=212</pre>
 *
 * <p>Cada línea empieza con un código de evento estable ({@code dominio.accion.resultado}, minúsculas) seguido de
 * pares {@code clave=valor}. El código es lo que se busca con {@code grep}; el texto libre no existe, así que nadie
 * tiene que adivinar qué significa una línea. Los pares se pasan como argumentos alternados, clave y valor:
 * {@code LogEvent.info(log, "reserva.hold.creado", "proceso", id, "usuario", userId)}.
 *
 * <p>Lo que esta clase garantiza (y prueba {@code LogEventTest}):
 * <ul>
 *   <li><b>Un evento, una línea.</b> Los saltos de línea y los caracteres de control de un valor se escapan: un
 *       valor que viene de afuera (una URL, un encabezado, un campo del cuerpo) no puede fabricar una línea falsa
 *       en el log (inyección de logs, OWASP Logging Cheat Sheet).</li>
 *   <li><b>Valores acotados.</b> Más de {@value #MAX_VALUE} caracteres se cortan con {@code …}.</li>
 *   <li><b>Sin formateo si el nivel está apagado.</b></li>
 *   <li><b>La traza solo en {@code ERROR}</b>, y una sola vez, donde se maneja la falla.</li>
 * </ul>
 *
 * <p>Qué <b>no</b> se pasa nunca como valor (la norma lo exige y los tests lo vigilan): tokens, contraseñas, teléfono,
 * nombres del paciente, la dirección de la cátedra, el {@code groupId} ni el cuerpo completo de un mensaje. Solo ids.
 * El detalle técnico suelto va con {@code log.debug} directo, que la norma permite.
 */
public final class LogEvent {

    /** Largo máximo de un valor; lo que sobra se reemplaza por {@code …}. */
    static final int MAX_VALUE = 200;

    private LogEvent() {
    }

    /** Un hito de negocio o un resultado normal: una línea por transición. */
    public static void info(Logger log, String evento, Object... campos) {
        if (log.isInfoEnabled()) {
            log.info(format(evento, campos));
        }
    }

    /** Una falla esperable que se manejó (la cátedra no responde, un reintento) o una degradación. */
    public static void warn(Logger log, String evento, Object... campos) {
        if (log.isWarnEnabled()) {
            log.warn(format(evento, campos));
        }
    }

    /** Un bug o algo que exige que alguien intervenga. Es el único nivel que lleva la traza completa. */
    public static void error(Logger log, String evento, Throwable causa, Object... campos) {
        if (log.isErrorEnabled()) {
            log.error(format(evento, campos), causa);
        }
    }

    /** Arma la línea. Visible para las pruebas. Un último campo sin valor se escribe con {@code -}. */
    static String format(String evento, Object... campos) {
        StringBuilder line = new StringBuilder(96).append("evento=").append(clean(evento));
        for (int i = 0; campos != null && i < campos.length; i += 2) {
            line.append(' ').append(key(campos[i])).append('=');
            line.append(i + 1 < campos.length ? value(campos[i + 1]) : "-");
        }
        return line.toString();
    }

    /** Las claves las escribe el programador, pero igual se dejan en {@code [a-z0-9_]} para no romper el formato. */
    private static String key(Object key) {
        return sanitize(String.valueOf(key), false);
    }

    /** El código del evento admite además el punto ({@code dominio.accion.resultado}). */
    private static String clean(String evento) {
        return evento == null || evento.isBlank() ? "-" : sanitize(evento, true);
    }

    private static String sanitize(String text, boolean allowDot) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || (allowDot && c == '.');
            out.append(ok ? c : '_');
        }
        return out.length() == 0 ? "_" : out.toString();
    }

    /**
     * Un valor simple se escribe tal cual. Uno vacío o con espacio, {@code =}, comilla, barra o carácter de control va
     * entre comillas y se escapa una sola vez: la comilla, la barra, el salto de línea, el retorno y el tabulador
     * salen como barra más letra, y el resto de los controles como barra, "u" y cuatro dígitos hexadecimales. Así un
     * salto de línea nunca llega al archivo como salto.
     */
    private static String value(Object value) {
        if (value == null) {
            return "-";
        }
        String text = value instanceof Enum<?> e ? e.name() : String.valueOf(value);
        boolean truncated = text.length() > MAX_VALUE;
        if (truncated) {
            text = text.substring(0, MAX_VALUE);
        }
        boolean quote = text.isEmpty();
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\\' -> { out.append("\\\\"); quote = true; }
                case '"' -> { out.append("\\\""); quote = true; }
                case '\n' -> { out.append("\\n"); quote = true; }
                case '\r' -> { out.append("\\r"); quote = true; }
                case '\t' -> { out.append("\\t"); quote = true; }
                case ' ', '=' -> { out.append(c); quote = true; }
                default -> {
                    if (Character.isISOControl(c)) {
                        out.append("\\u%04x".formatted((int) c));
                        quote = true;
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        if (truncated) {
            out.append('…');
        }
        return quote ? '"' + out.toString() + '"' : out.toString();
    }
}
