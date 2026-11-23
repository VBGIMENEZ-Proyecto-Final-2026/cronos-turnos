package ar.edu.um.cronos.config;

import ar.edu.um.cronos.TestKeys;
import ar.edu.um.cronos.application.port.out.PasswordHasher;
import ar.edu.um.cronos.application.port.out.TokenIssuer;
import ar.edu.um.cronos.application.port.out.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Sin clave privada JWT cronos no arranca: es más seguro que arrancar sin poder firmar o firmando con algo por defecto. */
class UsersConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(UsersConfig.class)
            .withBean(UserRepository.class, () -> mock(UserRepository.class))
            .withBean(PasswordHasher.class, () -> mock(PasswordHasher.class));

    @Test
    void sinClaveNoArranca() {
        runner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("CRONOS_JWT_PRIVATE_KEY");
        });
    }

    @Test
    void conClaveArrancaYTieneEmisorDeTokens() {
        runner.withPropertyValues("cronos.security.jwt.private-key=" + TestKeys.privatePem()).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TokenIssuer.class);
        });
    }

    @Test
    void conUnaClaveInvalidaNoArrancaYElMensajeNoLaRepite() {
        runner.withPropertyValues("cronos.security.jwt.private-key=esto-no-es-una-clave-secreta").run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("PKCS#8")
                    .doesNotContain("esto-no-es-una-clave-secreta");
        });
    }

    private static String rootMessage(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            all.append(t.getMessage()).append('\n');
        }
        return all.toString();
    }
}
