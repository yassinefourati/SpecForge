package [=pkg];

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * The modules keep to their boundaries (no cycles, no use of another module's internal package, dependencies as
 * declared in each package-info.java), and their documentation (PlantUML) is written to target/spring-modulith-docs.
 *
 * [=generatedNote]
 */
class ModularStructureTests {

    static final ApplicationModules MODULES = ApplicationModules.of([=api.applicationClass].class);

    @Test
    void modulesKeepToTheirBoundaries() {
        MODULES.verify();
    }

    @Test
    void writeDocumentation() {
        new Documenter(MODULES).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
