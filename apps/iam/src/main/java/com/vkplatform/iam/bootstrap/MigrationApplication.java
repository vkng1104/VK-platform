package com.vkplatform.iam.bootstrap;

import org.flywaydb.core.Flyway;

public final class MigrationApplication {
    private MigrationApplication() {
    }

    public static void main(String[] args) {
        String url = required("IAM_DATABASE_URL");
        String username = required("IAM_DATABASE_USERNAME");
        String password = required("IAM_DATABASE_PASSWORD");

        Flyway flyway = Flyway.configure()
                .dataSource(url, username, password)
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load();
        flyway.migrate();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        return value.strip();
    }
}
