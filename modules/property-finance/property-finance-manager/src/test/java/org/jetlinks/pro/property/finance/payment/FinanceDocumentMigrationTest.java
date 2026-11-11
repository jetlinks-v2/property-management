package org.jetlinks.pro.property.finance.payment;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.nio.file.Files;
import java.nio.file.Path;

class FinanceDocumentMigrationTest {
    @Test
    void oldNumbersAreFilledOnceWithoutChangingFactsAndOrphansRollback() throws Exception {
        try (var db =
                new GenericContainer<>("postgres:16-alpine")
                        .withEnv("POSTGRES_PASSWORD", "test-password")
                        .waitingFor(
                                Wait.forLogMessage(
                                        ".*database system is ready to accept connections.*\n",
                                        2))) {
            db.start();
            sql(
                    db,
                    """
CREATE TABLE pm_fin_receivable(id text primary key, create_time bigint, amount numeric);
CREATE TABLE pm_fin_payment(id text primary key, create_time bigint, source_type text);
CREATE TABLE pm_fin_refund(id text primary key, create_time bigint);
CREATE TABLE pm_fin_fund_account(id text primary key, project_id text, type text);
CREATE TABLE pm_fin_fund_entry(id text primary key, create_time bigint, project_id text, account_id text);
INSERT INTO pm_fin_receivable VALUES('bill',1794153600000,123.45);
INSERT INTO pm_fin_payment VALUES('cash',1794153600000,'RECEIVABLE'),('temp',1794153600000,'TEMPORARY');
INSERT INTO pm_fin_refund VALUES('refund',1794153600000);
INSERT INTO pm_fin_fund_account VALUES('deposit','p','DEPOSIT'),('advance','p','ADVANCE');
INSERT INTO pm_fin_fund_entry VALUES('d',1794153600000,'p','deposit'),('a',1794153600000,'p','advance');
""");
            var migration =
                    Files.readString(Path.of("../sql/migrate-finance-document-numbers.sql"));
            sql(db, migration);
            var before = sql(db, "SELECT id, bill_no, amount FROM pm_fin_receivable ORDER BY id");
            assertTrue(before.contains("bill|YS20261109H"), before);
            assertTrue(before.contains("123.45"));
            assertTrue(
                    sql(db, "SELECT bill_no FROM pm_fin_payment WHERE id='temp'").startsWith("LS"));
            assertTrue(
                    sql(db, "SELECT bill_no FROM pm_fin_payment WHERE id='cash'").startsWith("SS"));
            assertTrue(
                    sql(db, "SELECT bill_no FROM pm_fin_fund_entry WHERE id='d'").startsWith("YJ"));
            assertTrue(
                    sql(db, "SELECT bill_no FROM pm_fin_fund_entry WHERE id='a'").startsWith("YC"));
            assertTrue(sql(db, "SELECT bill_no FROM pm_fin_refund").startsWith("TK"));
            sql(db, migration);
            assertEquals(
                    before,
                    sql(db, "SELECT id, bill_no, amount FROM pm_fin_receivable ORDER BY id"));
            sql(
                    db,
                    "INSERT INTO pm_fin_receivable(id,create_time,amount)"
                        + " VALUES('second',1794153600000,10)");
            sql(db, migration);
            assertEquals("2", sql(db, "SELECT count(distinct bill_no) FROM pm_fin_receivable"));
            assertTrue(
                    sql(db, "SELECT id, bill_no, amount FROM pm_fin_receivable WHERE id='bill'")
                            .equals(before));
            sql(
                    db,
                    "INSERT INTO pm_fin_fund_entry(id,project_id,account_id)"
                        + " VALUES('orphan','p','missing');INSERT INTO pm_fin_receivable(id,amount)"
                        + " VALUES('rollback',1)");
            var failed =
                    db.execInContainer(
                            "psql", "-U", "postgres", "-v", "ON_ERROR_STOP=1", "-c", migration);
            assertNotEquals(0, failed.getExitCode());
            assertTrue(failed.getStderr().contains("Unnumbered fund_entry rows remain"));
            assertEquals(
                    "1",
                    sql(
                            db,
                            "SELECT count(*) FROM pm_fin_receivable WHERE id='rollback' AND bill_no"
                                + " IS NULL"));
        }
    }

    private String sql(GenericContainer<?> db, String sql) throws Exception {
        var result =
                db.execInContainer(
                        "psql", "-U", "postgres", "-At", "-v", "ON_ERROR_STOP=1", "-c", sql);
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout().strip();
    }
}
