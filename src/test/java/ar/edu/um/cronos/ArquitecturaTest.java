package ar.edu.um.cronos;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Reglas de dependencia, las mismas que en atlas (ADR 0003): el dominio no conoce frameworks ni capas
 * externas y la aplicación no conoce adaptadores. Todavía no hay clases en esos paquetes, por eso las
 * reglas permiten paquetes vacíos; se endurecen cuando aparezca el primer caso de uso.
 */
@AnalyzeClasses(packages = "ar.edu.um.cronos", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule dominioSinFrameworks = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..", "..adapters..", "..config..", "org.springframework..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule aplicacionSinAdaptadores = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..adapters..", "..config..", "org.springframework.web..", "org.springframework.security..",
                    "org.springframework.kafka..", "tools.jackson..", "com.fasterxml.jackson..")
            .allowEmptyShould(true);
}
