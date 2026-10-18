package [=pkg];

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The hexagon's rules (ARCH-6): the domain (models and ports) knows no framework and no adapter; the application
 * knows the domain, not the adapters. Adapters may use anything.
 *
 * [=generatedNote]
 */
@AnalyzeClasses(packages = "[=basePackage]", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainKnowsNoFramework = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..", "javax.persistence..",
                    "org.hibernate..", "lombok..");

    @ArchTest
    static final ArchRule domainKnowsNoAdapter = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule applicationKnowsNoAdapter = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");
}
