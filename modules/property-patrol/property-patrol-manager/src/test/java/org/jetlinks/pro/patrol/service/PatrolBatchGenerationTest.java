package org.jetlinks.pro.patrol.service;

import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.integration.PatrolProjectContext;
import org.jetlinks.pro.patrol.web.PatrolTaskController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 日期批量生成使用真实SQL、事务和租户资产权限，保留单计划调用及重跑防重。 */
class PatrolBatchGenerationTest {
    private PatrolWorkflowTest fixture;

    @BeforeEach
    void setup() {
        fixture = new PatrolWorkflowTest();
        fixture.setup();
    }

    @AfterEach
    void close() {
        fixture.close();
    }

    private void plan(String id, Consumer<PatrolPlanEntity> change) {
        var plan = fixture.plans.findById("plan").block();
        plan.setId(id);
        change.accept(plan);
        fixture.plans.insert(plan).block();
    }

    @Test
    void dateOnlyGeneratesAuthorizedActivePlansAndReusesExistingTasks() {
        var day = LocalDate.now();
        plan("allowed", plan -> {});
        plan("foreign-tenant", plan -> plan.setExtension("tenantId", "south"));
        plan("foreign-app", plan -> plan.setScopeKey("other-app"));
        plan("invisible-project", plan -> plan.setProjectId("other-project"));
        plan("disabled", plan -> plan.setEnabled(false));
        plan("future", plan -> plan.setEffectiveFrom(day.plusDays(1)));
        plan("expired", plan -> plan.setEffectiveTo(day.minusDays(1)));
        plan("role-only", plan -> { plan.setStandardId(null); plan.setRule(null); });
        var controller = new PatrolTaskController(fixture.tasks, fixture.generator);
        var request = new PatrolTaskController.GenerateRequest(null, day);

        var rows = controller.generate(request).contextWrite(fixture.identity).collectList().block();
        assertEquals(Set.of("plan", "allowed"),
                Set.copyOf(rows.stream().map(PatrolTaskEntity::getPlanId).toList()));
        assertEquals(2, rows.size());
        assertEquals(2, fixture.generationRepository.createQuery().count().block());

        var replay = controller.generate(request).contextWrite(fixture.identity)
                .map(PatrolTaskEntity::getId).collectList().block();
        assertEquals(Set.copyOf(rows.stream().map(PatrolTaskEntity::getId).toList()), Set.copyOf(replay));
        assertEquals(2, fixture.tasks.createQuery().count().block());
    }

    @Test
    void emptyProjectScopeDoesNotGenerateAnyTasks() {
        var identity = fixture.identity.put(PatrolProjectContext.class,
                new PatrolProjectContext(List.of(), null, "app"));
        assertTrue(fixture.generator.generate(LocalDate.now()).contextWrite(identity)
                .collectList().block().isEmpty());
        assertEquals(0, fixture.generationRepository.createQuery().count().block());
    }

    @Test
    void explicitPlanKeepsOriginalSinglePlanBehavior() {
        plan("another", plan -> {});
        var controller = new PatrolTaskController(fixture.tasks, fixture.generator);
        var rows = controller.generate(new PatrolTaskController.GenerateRequest("plan", LocalDate.now()))
                .contextWrite(fixture.identity).collectList().block();
        assertEquals(List.of("plan"), rows.stream().map(PatrolTaskEntity::getPlanId).toList());
        assertEquals(1, fixture.generationRepository.createQuery().count().block());
    }
}
