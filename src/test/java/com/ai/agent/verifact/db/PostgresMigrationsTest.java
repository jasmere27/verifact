package com.ai.agent.verifact.db;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every migration, including the Postgres-only ones, on a real Postgres. Tests otherwise use H2, which
 * can't run them; V7 once failed only in production. A statement timeout turns a lock wait into a failure.
 */
class PostgresMigrationsTest {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void start() throws Exception {
        postgres = EmbeddedPostgres.builder().setServerConfig("statement_timeout", "20s").start();
    }

    @AfterAll
    static void stop() throws Exception {
        postgres.close();
    }

    @Test
    void allMigrationsApplyAndEveryTableHasRowLevelSecurity() throws Exception {
        // Like Supabase: the backend's role owns the tables but is not a superuser; "anon" stands for the Data API.
        try (Connection admin = postgres.getPostgresDatabase().getConnection()) {
            admin.createStatement().execute("CREATE ROLE app LOGIN NOSUPERUSER");
            admin.createStatement().execute("CREATE DATABASE appdb OWNER app");
            admin.createStatement().execute("CREATE ROLE anon NOLOGIN");
            admin.createStatement().execute("GRANT anon TO app");
        }
        DataSource ds = postgres.getDatabase("app", "appdb");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration", "classpath:db/vendor/postgresql").load().migrate();

        try (Connection c = ds.getConnection()) {
            // The owner (the backend) still reads and writes.
            c.createStatement().execute("INSERT INTO app_users (id, email, created_at, updated_at) "
                    + "VALUES ('6f1c2a7e-5b9d-4c3e-8a1f-2d3b4c5e6f70', 'a@example.com', now(), now())");
            assertThat(count(c, "app_users")).isEqualTo(1);
            // A Data API role, even with SELECT granted, sees nothing.
            c.createStatement().execute("GRANT SELECT ON app_users, news_reviews TO anon");
            c.createStatement().execute("SET ROLE anon");
            assertThat(count(c, "app_users")).isZero();
            c.createStatement().execute("RESET ROLE");
        }

        try (Connection c = ds.getConnection();
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT tablename, rowsecurity FROM pg_tables WHERE schemaname = 'public' ORDER BY tablename")) {
            List<String> withoutRls = new ArrayList<>();
            List<String> tables = new ArrayList<>();
            while (rs.next()) {
                tables.add(rs.getString(1));
                if (!rs.getBoolean(2) && !rs.getString(1).equals("flyway_schema_history")) {
                    withoutRls.add(rs.getString(1));
                }
            }
            assertThat(tables).contains("verifications", "news_reviews", "research_workspaces", "app_users", "memberships");
            // Without RLS, the Supabase Data API would expose a table to anyone holding the public key.
            assertThat(withoutRls).as("tables without row-level security").isEmpty();
        }
    }

    private static long count(Connection c, String table) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
