package ar.edu.um.cronos;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

/**
 * Captura lo que se escribe en el log mientras dura el bloque try, para comprobar que un secreto no aparece. Es más
 * confiable que capturar la consola: mira los eventos de logback directamente, con su excepción incluida.
 */
public final class LogCapture implements AutoCloseable {

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final ch.qos.logback.classic.Level previous = root.getLevel();

    public LogCapture() {
        // El consumidor de Kafka escribe desde su propio hilo mientras la prueba lee: una lista común no es segura.
        appender.list = new java.util.concurrent.CopyOnWriteArrayList<>();
        root.setLevel(ch.qos.logback.classic.Level.TRACE);
        appender.start();
        root.addAppender(appender);
    }

    /** Todo lo registrado hasta ahora: mensajes formateados y excepciones. */
    public String text() {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }

    /** Lo registrado, sin lo que escribieron los loggers que empiezan con alguno de los prefijos dados. */
    public String textExcluding(String... loggerPrefixes) {
        StringBuilder text = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            boolean excluded = false;
            for (String prefix : loggerPrefixes) {
                excluded |= event.getLoggerName().startsWith(prefix);
            }
            if (excluded) {
                continue;
            }
            text.append(event.getFormattedMessage()).append('\n');
            if (event.getThrowableProxy() != null) {
                text.append(ThrowableProxyUtil.asString(event.getThrowableProxy())).append('\n');
            }
        }
        return text.toString();
    }

    public int events() {
        return appender.list.size();
    }

    /** Los eventos tal cual los vio logback (nivel, mensaje, excepción y MDC), en el orden en que se escribieron. */
    public java.util.List<ILoggingEvent> list() {
        return java.util.List.copyOf(appender.list);
    }

    /** Las líneas de evento ({@code evento=...}) registradas con ese código, para comprobar un hito concreto. */
    public java.util.List<ILoggingEvent> eventos(String codigo) {
        return list().stream().filter(e -> e.getFormattedMessage().startsWith("evento=" + codigo + " ")
                || e.getFormattedMessage().equals("evento=" + codigo)).toList();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(previous);
    }
}
