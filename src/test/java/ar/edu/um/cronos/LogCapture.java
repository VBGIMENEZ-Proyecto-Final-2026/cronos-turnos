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

    public int events() {
        return appender.list.size();
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(previous);
    }
}
