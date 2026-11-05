package org.jetlinks.pro.smartpark.handover.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;

import java.nio.file.Files;
import java.nio.file.Path;

/** 在独立 PostgreSQL 执行部署原脚本，验证迁移和保护性回滚，不连接业务库。 */
class HandoverEquipmentMigrationTest {
    private static final GenericContainer<?> POSTGRES =
            new GenericContainer<>("postgres:16-alpine")
                    .withEnv("POSTGRES_PASSWORD", "test-password")
                    .withExposedPorts(5432);

    @BeforeAll
    static void start() throws Exception {
        POSTGRES.start();
        for (int attempt = 0; attempt < 100; attempt++) {
            if (POSTGRES.execInContainer("pg_isready", "-U", "postgres").getExitCode() == 0) {
                return;
            }
            Thread.sleep(100);
        }
        fail("临时 PostgreSQL 未就绪");
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void prepare() throws Exception {
        execute(
                """
                DROP TABLE IF EXISTS sp_handover_equipment_fields_backup;
                DROP TABLE IF EXISTS sp_handover_equipment;
                CREATE TABLE sp_handover_equipment(
                  id text PRIMARY KEY, supplier text, production_date bigint,
                  version bigint, properties text);
                INSERT INTO sp_handover_equipment VALUES
                  ('migrated',NULL,NULL,3,'{"supplier":"历史供应商","productionDate":"2025-03-01"}'),
                  ('untouched','现有供应商',100,8,'{}');
                """);
    }

    @Test
    void rollbackRestoresOnlyMigratedRowsAndPreservesUnrelatedEdits() throws Exception {
        execute(script("04-equipment-fields-migrate.sql"));
        assertEquals(
                "历史供应商|4|true",
                execute(
                                """
                                SELECT supplier,version,production_date IS NOT NULL
                                FROM sp_handover_equipment WHERE id='migrated'
                                """)
                        .replace("|t", "|true"));
        execute(
                """
                UPDATE sp_handover_equipment SET supplier='用户改过',version=version+1
                  WHERE id='untouched';
                INSERT INTO sp_handover_equipment VALUES('new','新增供应商',200,0,'{}');
                """);
        execute(script("05-equipment-fields-rollback.sql"));
        assertEquals(
                "用户改过|9",
                execute(
                        """
                        SELECT supplier,version FROM sp_handover_equipment WHERE id='untouched'
                        """));
        assertEquals(
                "true|true|3",
                execute(
                                """
                                SELECT supplier IS NULL,production_date IS NULL,version
                                  FROM sp_handover_equipment WHERE id='migrated'
                                """)
                        .replace("t|t|", "true|true|"));
        assertEquals("新增供应商", execute("SELECT supplier FROM sp_handover_equipment WHERE id='new'"));
    }

    @Test
    void editedMigratedRowRejectsTheWholeRollback() throws Exception {
        execute(script("04-equipment-fields-migrate.sql"));
        execute(
                "UPDATE sp_handover_equipment SET supplier='新值',version=version+1 WHERE"
                    + " id='migrated'");
        assertNotEquals(
                0,
                POSTGRES.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-c",
                                script("05-equipment-fields-rollback.sql"))
                        .getExitCode());
        assertEquals(
                "新值|5",
                execute("SELECT supplier,version FROM sp_handover_equipment WHERE id='migrated'"));
    }

    @Test
    void deletedMigratedRowRejectsRollback() throws Exception {
        execute(script("04-equipment-fields-migrate.sql"));
        execute("DELETE FROM sp_handover_equipment WHERE id='migrated'");
        assertNotEquals(
                0,
                POSTGRES.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-c",
                                script("05-equipment-fields-rollback.sql"))
                        .getExitCode());
    }

    @Test
    void invalidLegacyDateLeavesNeitherPartialUpdatesNorBackup() throws Exception {
        execute(
                """
                INSERT INTO sp_handover_equipment VALUES(
                  'invalid',NULL,NULL,0,'{"productionDate":"not-a-date"}')
                """);
        assertNotEquals(
                0,
                POSTGRES.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-c",
                                script("04-equipment-fields-migrate.sql"))
                        .getExitCode());
        assertEquals("3", execute("SELECT version FROM sp_handover_equipment WHERE id='migrated'"));
        assertEquals("", execute("SELECT to_regclass('sp_handover_equipment_fields_backup')"));
    }

    private static String execute(String sql) throws Exception {
        var result =
                POSTGRES.execInContainer(
                        "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1", "-At", "-c", sql);
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout().trim();
    }

    private static String script(String name) throws Exception {
        for (Path base = Path.of("").toAbsolutePath(); base != null; base = base.getParent()) {
            Path file = base.resolve("modules/property-handover/docs/sql").resolve(name);
            if (Files.exists(file)) {
                return Files.readString(file).replace("\ufeff", "");
            }
        }
        throw new IllegalStateException("找不到部署脚本：" + name);
    }
}
