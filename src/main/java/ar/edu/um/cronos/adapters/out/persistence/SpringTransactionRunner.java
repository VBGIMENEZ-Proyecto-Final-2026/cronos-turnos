package ar.edu.um.cronos.adapters.out.persistence;

import ar.edu.um.cronos.application.port.out.TransactionRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * {@link TransactionRunner} sobre la transacción de Spring: lo que se escriba con JPA y con {@code JdbcTemplate}
 * dentro del bloque comparte la misma conexión, y una excepción no verificada deshace todo.
 */
@Component
class SpringTransactionRunner implements TransactionRunner {

    private final TransactionTemplate template;

    SpringTransactionRunner(PlatformTransactionManager transactionManager) {
        this.template = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T inTransaction(Supplier<T> work) {
        return template.execute(status -> work.get());
    }
}
