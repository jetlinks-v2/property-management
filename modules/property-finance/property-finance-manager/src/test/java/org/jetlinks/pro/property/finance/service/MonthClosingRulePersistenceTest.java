package org.jetlinks.pro.property.finance.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.finance.entity.MonthClosingRuleOperationEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.UUID;

@SpringBootTest(
        classes = MonthClosingRulePersistenceTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MonthClosingRulePersistenceTest extends MonthClosingPersistenceTest {
    @Autowired MonthClosingRuleService ruleService;
    @Autowired ReactiveRepository<MonthClosingRuleOperationEntity, String> ruleOperations;

    @Test
    void defaultRuleIsReadOnlyAndSavedRuleCannotChangeClosedBoundary() {
        var defaultRule = ruleService.get(project).contextWrite(allowed()).block(WAIT);
        assertNull(defaultRule.version());
        assertFalse(defaultRule.requireReceivableReview());
        assertEquals(0, controls.createQuery().where("projectId", project).count().block(WAIT));
        var saved =
                ruleService
                        .save(project, new MonthClosingRuleService.Input(null, true, "启用应收审核"))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertTrue(saved.requireReceivableReview());
        january();
        var boundary =
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getClosedThrough();
        rejects(
                ruleService.save(
                        project, new MonthClosingRuleService.Input(saved.version(), false, "过期覆盖")),
                409);
        var current = ruleService.get(project).contextWrite(allowed()).block(WAIT);
        var next =
                ruleService
                        .save(
                                project,
                                new MonthClosingRuleService.Input(
                                        current.version(), false, "调整审核规则"))
                        .contextWrite(allowed())
                        .block(WAIT);
        assertFalse(next.requireReceivableReview());
        assertEquals(
                boundary,
                controls.createQuery()
                        .where("projectId", project)
                        .fetchOne()
                        .block(WAIT)
                        .getClosedThrough());
        assertEquals(
                2, ruleOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void concurrentInitialSaveUsesOneVersionAndExplicitConflict() {
        var input = new MonthClosingRuleService.Input(null, true, "首次保存");
        var attempts =
                Flux.merge(
                                ruleService.save(project, input).materialize(),
                                ruleService.save(project, input).materialize())
                        .collectList()
                        .contextWrite(allowed())
                        .block(WAIT);
        assertEquals(1, attempts.stream().filter(reactor.core.publisher.Signal::isOnNext).count());
        assertEquals(
                1,
                attempts.stream()
                        .filter(
                                s ->
                                        s.isOnError()
                                                && TaxItemPersistenceTest.status(
                                                        s.getThrowable(), 409))
                        .count());
        assertEquals(
                1, ruleOperations.createQuery().where("projectId", project).count().block(WAIT));
    }

    @Test
    void failedRuleAuditRollsBackRuleAndInitialControl() throws Exception {
        var constraint = "rule_fail_" + UUID.randomUUID().toString().replace("-", "");
        var db = HouseSettingPersistenceTest.DB;
        assertEquals(
                0,
                db.execInContainer(
                                "psql",
                                "-U",
                                "postgres",
                                "-d",
                                "setting_test",
                                "-c",
                                "ALTER TABLE pm_fin_month_rule_log ADD CONSTRAINT "
                                        + constraint
                                        + " CHECK (project_id <> '"
                                        + project
                                        + "')")
                        .getExitCode());
        try {
            StepVerifier.create(
                            ruleService
                                    .save(
                                            project,
                                            new MonthClosingRuleService.Input(null, true, "规则审计"))
                                    .contextWrite(allowed()))
                    .expectError()
                    .verify(WAIT);
            assertEquals(0, controls.createQuery().where("projectId", project).count().block(WAIT));
            assertEquals(
                    0,
                    ruleOperations.createQuery().where("projectId", project).count().block(WAIT));
        } finally {
            assertEquals(
                    0,
                    db.execInContainer(
                                    "psql",
                                    "-U",
                                    "postgres",
                                    "-d",
                                    "setting_test",
                                    "-c",
                                    "ALTER TABLE pm_fin_month_rule_log DROP CONSTRAINT "
                                            + constraint)
                            .getExitCode());
        }
    }

    @Configuration
    @Import({MonthClosingPersistenceTest.Config.class, MonthClosingRuleService.class})
    static class Config {}
}
