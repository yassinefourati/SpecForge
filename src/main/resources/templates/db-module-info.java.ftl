/**
 * Module "[=appModule.displayName]": tables [=appModule.tables?join(", ")].
 * Other modules may use this package (its controllers and records) only; the entities, repositories and services in
 * internal are the module's own. It depends on the shared module alone: a table of another module is referred to by
 * its id (ModularStructureTests checks it).
 *
 * [=generatedNote]
 */
@ApplicationModule(displayName = "[=appModule.displayName?j_string]", allowedDependencies = "shared")
package [=pkg];

import org.springframework.modulith.ApplicationModule;
