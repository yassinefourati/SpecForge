package io.specforge.gen;

import io.specforge.config.GeneratorConfig;
import io.specforge.model.DbModel.DbEntity;
import io.specforge.util.Names;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ARCH-1: the package of every generated class, per entity. LAYERED is SpecForge's original layout (one package per
 * layer, shared by every table); FEATURE puts each table's classes in a package of their own
 * ({@code com.example.shop.customer.CustomerService}). What all tables share (PageResponse, ValueConversions,
 * the exception handler, security, the application) stays in the layer packages.
 * <p>
 * Templates see the current entity's packages as {@code entityPackage}, {@code modelPackage}, … and look up
 * another table's with {@code pkgOf("Customer", "entities")}.
 */
public final class PackageLayout {

    /** the layers whose classes are per entity */
    static final List<String> PER_ENTITY = List.of("entities", "models", "repositories", "services", "controllers");

    private final GeneratorConfig cfg;
    private final String architecture;

    private PackageLayout(GeneratorConfig cfg) {
        this.cfg = cfg;
        this.architecture = cfg.architecture == null ? "LAYERED" : cfg.architecture.toUpperCase(Locale.ROOT);
    }

    static PackageLayout of(GeneratorConfig cfg) {
        return new PackageLayout(cfg);
    }

    boolean layered() {
        return architecture.equals("LAYERED");
    }

    /** Fills {@code e.packages} for every per-entity layer (and, hexagonal, the domain and outbound port packages). */
    void assign(DbEntity e) {
        Map<String, String> p = new LinkedHashMap<>();
        for (String layer : PER_ENTITY) p.put(layer, packageOf(layer, e));
        if (hexagonal()) {
            String root = cfg.basePackage + "." + feature(e);
            p.put("domain", root + ".domain.model");
            p.put("portOut", root + ".domain.port.out");
        }
        e.packages = p;
    }

    boolean hexagonal() {
        return architecture.equals("HEXAGONAL");
    }

    boolean modular() {
        return architecture.equals("MODULAR");
    }

    String packageOf(String layer, DbEntity e) {
        if (e == null || layered() || !PER_ENTITY.contains(layer)) return cfg.layerPackage(layer);
        if (modular()) return modulePackage(e.module, layer);
        if (hexagonal()) {
            // ARCH-6: ports and adapters per table
            String root = cfg.basePackage + "." + feature(e);
            return switch (layer) {
                case "models" -> root + ".domain.port.in";
                case "services" -> root + ".application";
                case "controllers" -> root + ".adapter.in.web";
                default -> root + ".adapter.out.persistence";
            };
        }
        return cfg.basePackage + "." + feature(e);
    }

    /**
     * MODULAR (ARCH-4): a module's API (its controllers and records) in {@code base.<module>}, the rest in
     * {@code base.<module>.internal}, which other modules may not use (Spring Modulith's convention).
     */
    String modulePackage(String module, String layer) {
        String root = cfg.basePackage + "." + module;
        return layer.equals("models") || layer.equals("controllers") ? root : root + ".internal";
    }

    /** Where what all tables share lives: the layer packages, or the shared module when modular. */
    String global(String layer) {
        if (modular() && (layer.equals("exceptions") || layer.equals("models") || layer.equals("services"))) return cfg.basePackage + ".shared";
        return cfg.layerPackage(layer);
    }

    /** A use case's files: with its tables' module when modular, else the layer packages. */
    String useCase(String layer, String module) {
        return modular() && module != null && !module.isEmpty() ? modulePackage(module, layer) : cfg.layerPackage(layer);
    }

    /** The package segment of a table's feature: its class name in lower case, e.g. "orderitem". */
    static String feature(DbEntity e) {
        String s = e.className.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return Names.isJavaIdentifier(s) && !Names.RESERVED_CLASS_NAMES.contains(s) ? s : s + "s";
    }
}
