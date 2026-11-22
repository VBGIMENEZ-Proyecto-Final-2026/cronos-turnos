package ar.edu.um.cronos.application.port.out;

import java.util.function.Supplier;

/**
 * Corre un bloque como una sola transacción: o se aplica todo o no se aplica nada. Lo implementa el adaptador de
 * persistencia, para que la capa de aplicación pueda pedir atomicidad sin conocer Spring.
 *
 * <p>Si el bloque lanza una excepción no verificada, la transacción se deshace entera y la excepción sube tal cual.
 */
public interface TransactionRunner {

    <T> T inTransaction(Supplier<T> work);
}
