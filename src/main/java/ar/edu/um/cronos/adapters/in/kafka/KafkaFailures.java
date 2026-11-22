package ar.edu.um.cronos.adapters.in.kafka;

import ar.edu.um.cronos.application.port.out.ReservationConflictException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.TransactionException;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;

/**
 * Clasifica por qué falló aplicar un mensaje, para decidir qué hacer con él. La diferencia importa: con una sola partición,
 * saltear un mensaje rompe el orden de los eventos de un proceso y descartarlo puede perder una confirmación.
 *
 * <ul>
 *   <li><b>Transitoria:</b> la infraestructura no responde ahora (la base está caída o saturada, se cortó la conexión,
 *       otro escritor siguió cambiando el proceso). Se reintenta con espera creciente, sin límite de intentos: el mensaje
 *       no se pierde ni se saltea.</li>
 *   <li><b>Cualquier otra:</b> el mensaje falla por sí mismo (por ejemplo, una restricción de la base). Reintentarlo para
 *       siempre trabaría la partición: se reintenta pocas veces y se estaciona con su diagnóstico.</li>
 * </ul>
 */
final class KafkaFailures {

    private KafkaFailures() {
    }

    /** La excepción que lanzó el listener, sin el envoltorio del contenedor. */
    static Throwable cause(Throwable error) {
        Throwable current = error;
        while (current instanceof ListenerExecutionFailedException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /** Si alguna causa de la cadena indica que es la infraestructura la que falla ahora y no el mensaje. */
    static boolean isTransient(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TransientDataAccessException || t instanceof DataAccessResourceFailureException
                    || t instanceof TransactionException || t instanceof ReservationConflictException
                    || t instanceof SQLTransientException || t instanceof SQLRecoverableException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && isConnectionOrResourceState(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /** Clases 08 (conexión), 53 (recursos insuficientes) y 57 (operador: apagado o reinicio) de SQLSTATE. */
    private static boolean isConnectionOrResourceState(String sqlState) {
        return sqlState.startsWith("08") || sqlState.startsWith("53") || sqlState.startsWith("57");
    }

    /** Solo el nombre de la clase, nunca el mensaje: el de una falla de base de datos puede traer la fila con los datos. */
    static String name(Throwable error) {
        return error == null ? "-" : error.getClass().getSimpleName();
    }

    /** El nombre de la clase de la causa más profunda. */
    static String rootName(Throwable error) {
        Throwable root = error;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return name(root);
    }
}
