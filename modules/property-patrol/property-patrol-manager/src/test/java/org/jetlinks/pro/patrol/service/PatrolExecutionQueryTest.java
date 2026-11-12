package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;

/** 计数省去快照关联后仍保留页数据、嵌套筛选和计划资产边界。 */
class PatrolExecutionQueryTest {
    @Test
    void storedAndSnapshotFiltersKeepTheSameAuthorizedCountAndPage() {
        var f = new PatrolWorkflowTest();
        f.setup();
        try {
            f.db.enableTenantQueries();
            var first = f.seedTask(2);
            var second = f.tasks.findById(first.getId()).block();
            second.setId("second"); second.setPeriodNo(2); second.setStartTime(first.getStartTime() + 1);
            second.setCategoryId("second-category"); second.setCategoryName("Second");
            f.tasks.insert(second).block();
            fact(f, "one", "task", "plan");
            fact(f, "two", "second", "plan");
            f.seedPlan("hidden-plan", "south");
            fact(f, "hidden", "second", "hidden-plan");

            var stored = QueryParamEntity.newQuery().is("userId", "alice").getParam();
            stored.setPageSize(1);
            var page = authorized(stored).flatMap(f.executions::queryPager).contextWrite(f.identity).block();
            assertEquals(2, page.getTotal()); assertEquals(1, page.getData().size());
            assertNotNull(page.getData().get(0).getPeriodStartTime());
            assertEquals(2, authorized(stored).flatMap(f.executions::count).contextWrite(f.identity).block());

            var category = QueryParamEntity.newQuery().is("categoryId", "second-category").getParam();
            var filtered = authorized(category).flatMap(f.executions::queryPager).contextWrite(f.identity).block();
            assertEquals(1, filtered.getTotal()); assertEquals("two", filtered.getData().get(0).getId());
            var nested = QueryParamEntity.newQuery().nest(q -> q.is("categoryId", "second-category")
                    .or("userId", "nobody")).getParam();
            assertEquals(1, authorized(nested).flatMap(f.executions::count).contextWrite(f.identity).block());
            var period = QueryParamEntity.newQuery().gt("startTime", first.getStartTime()).getParam();
            assertEquals(1, authorized(period).flatMap(f.executions::count).contextWrite(f.identity).block());
            var role = QueryParamEntity.newQuery().is("roleId", "post").getParam();
            assertEquals(2, authorized(role).flatMap(f.executions::count).contextWrite(f.identity).block());
            var empty = QueryParamEntity.newQuery().is("userId", "nobody").getParam();
            assertEquals(0, authorized(empty).flatMap(f.executions::queryPager).contextWrite(f.identity).block().getTotal());
        } finally {
            f.close();
        }
    }

    private Mono<QueryParamEntity> authorized(QueryParamEntity query) {
        return AssetsHolder.injectQueryParam(query, PatrolAssetType.patrolPlan, "planId");
    }

    private void fact(PatrolWorkflowTest f, String id, String task, String plan) {
        var fact = new PatrolExecutionEntity();
        fact.setId(id); fact.setTaskId(task); fact.setPlanId(plan); fact.setPointId("point");
        fact.setUserId("alice"); fact.setUserName("Alice"); fact.setRequestId(id);
        fact.setReceiveTime(System.currentTimeMillis());
        f.executions.insert(fact).block();
    }
}
