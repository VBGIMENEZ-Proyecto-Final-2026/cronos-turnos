package ar.edu.um.cronos;

import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;

/**
 * Sube los niveles de log para las pruebas que comprueban que un secreto no aparece en los logs. Las propiedades
 * {@code logging.level.*} de {@code @SpringBootTest} solo valen cuando se crea el contexto, y el nivel de log es
 * global a la JVM: si otra clase de prueba crea después un contexto con los niveles por defecto, los deja bajos y
 * la prueba de logs pasaría vacía (nada que mirar). Por eso estas pruebas los vuelven a fijar antes de cada caso.
 */
public final class LogLevels {

    private LogLevels() {
    }

    public static void altos() {
        LoggingSystem logging = LoggingSystem.get(LogLevels.class.getClassLoader());
        logging.setLogLevel("org.springframework.security", LogLevel.TRACE);
        logging.setLogLevel("org.hibernate.SQL", LogLevel.DEBUG);
        logging.setLogLevel("org.hibernate.orm.jdbc.bind", LogLevel.TRACE);
        logging.setLogLevel("ar.edu.um.cronos", LogLevel.TRACE);
        logging.setLogLevel("org.springframework.test", LogLevel.INFO);
    }
}
