package io.specforge.source.jdbc;

import io.specforge.source.SourceKind;

import java.sql.Driver;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Driver self-check (DRV-3): which engines' drivers are in this build, with their versions. Run once at startup. */
public final class Drivers {

    private static volatile List<Map<String, Object>> report;

    /** Maven coordinates and license of each bundled driver (DRV-5, THIRD-PARTY-NOTICES). */
    private static final Map<SourceKind, String[]> ARTIFACTS = Map.of(
        SourceKind.POSTGRESQL, new String[] {"org.postgresql", "postgresql", "BSD-2-Clause"},
        SourceKind.MYSQL, new String[] {"com.mysql", "mysql-connector-j", "GPL-2.0 with Universal FOSS Exception"},
        SourceKind.MARIADB, new String[] {"org.mariadb.jdbc", "mariadb-java-client", "LGPL-2.1"},
        SourceKind.SQLSERVER, new String[] {"com.microsoft.sqlserver", "mssql-jdbc", "MIT"},
        SourceKind.ORACLE, new String[] {"com.oracle.database.jdbc", "ojdbc17", "Oracle Free Use Terms and Conditions"},
        SourceKind.DB2, new String[] {"com.ibm.db2", "jcc", "IBM International Program License (redistribution to be confirmed, Q1)"},
        SourceKind.SQLITE, new String[] {"org.xerial", "sqlite-jdbc", "Apache-2.0"},
        SourceKind.H2, new String[] {"com.h2database", "h2", "MPL-2.0 or EPL-1.0"});

    /** Driver versions recorded at build time from pom.xml; shading drops the drivers' own manifests. */
    private static String pomVersion(String group, String artifact) {
        try (var in = Drivers.class.getResourceAsStream("/specforge/drivers.properties")) {
            if (in == null) return null;
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            String v = p.getProperty(artifact);
            return v == null || v.startsWith("${") ? null : v;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    public static synchronized List<Map<String, Object>> report() {
        if (report != null) return report;
        List<Map<String, Object>> out = new ArrayList<>();
        for (SourceKind k : SourceKind.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("engine", k.name());
            m.put("label", k.label);
            m.put("driverClass", k.driverClass);
            String[] artifact = ARTIFACTS.get(k);
            m.put("artifact", artifact[0] + ":" + artifact[1]);
            m.put("license", artifact[2]);
            try {
                Class<?> c = Class.forName(k.driverClass);
                Driver d = (Driver) c.getDeclaredConstructor().newInstance();
                String version = pomVersion(artifact[0], artifact[1]);
                if (version == null && c.getPackage() != null) version = c.getPackage().getImplementationVersion();
                m.put("available", true);
                m.put("version", version != null ? version : d.getMajorVersion() + "." + d.getMinorVersion());
            } catch (ClassNotFoundException e) {
                m.put("available", false);
                m.put("status", "not in this build");
            } catch (Throwable e) {
                m.put("available", false);
                m.put("status", "failed to load: " + e.getClass().getSimpleName());
            }
            out.add(m);
        }
        report = List.copyOf(out);
        return report;
    }

    public static boolean available(SourceKind kind) {
        for (Map<String, Object> m : report()) if (m.get("engine").equals(kind.name())) return Boolean.TRUE.equals(m.get("available"));
        return false;
    }
}
