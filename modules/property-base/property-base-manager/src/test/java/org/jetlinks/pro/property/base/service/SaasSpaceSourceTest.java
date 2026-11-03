package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;

import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.base.space.SaasSpaceInfo;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class SaasSpaceSourceTest {
    @Test
    void rpcPagerRestoresTypedSourceDto() {
        var response =
                (PagerResult<?>)
                        SaasSpaceSource.pageCommand(new QueryParamEntity())
                                .createResponseData(
                                        Map.of(
                                                "total",
                                                1,
                                                "pageIndex",
                                                0,
                                                "pageSize",
                                                200,
                                                "data",
                                                List.of(
                                                        Map.of(
                                                                "id",
                                                                "room",
                                                                "name",
                                                                "101",
                                                                "parentId",
                                                                "floor",
                                                                "sortIndex",
                                                                10))));
        var node = assertInstanceOf(SaasSpaceInfo.class, response.getData().get(0));
        assertEquals("room", node.getId());
        assertEquals("floor", node.getParentId());
        assertEquals(10L, node.getSortIndex());
    }

    @Test
    void sourceTypesAreResolvedByIdAndTypeFailureRejectsWholeSnapshot() {
        var room = SpaceSnapshotTest.node("room", null, "101");
        room.setSpaceTypeId("room-type");
        var source =
                new SaasSpaceSource() {
                    @Override
                    protected Mono<PagerResult<SaasSpaceInfo>> page(QueryParamEntity query) {
                        return Mono.just(PagerResult.of(1, List.of(room), query));
                    }

                    @Override
                    protected Mono<PagerResult<SaasSpaceInfo>> typePage(QueryParamEntity query) {
                        return Mono.just(
                                PagerResult.of(
                                        1,
                                        List.of(SpaceSnapshotTest.node("room-type", null, "房间")),
                                        query));
                    }
                };
        StepVerifier.create(source.fetchAll())
                .assertNext(
                        rows -> {
                            assertEquals("room-type", rows.get(0).getSpaceTypeId());
                            assertEquals("房间", rows.get(0).getSpaceTypeName());
                        })
                .verifyComplete();
        var failed =
                new SaasSpaceSource() {
                    @Override
                    protected Mono<PagerResult<SaasSpaceInfo>> page(QueryParamEntity query) {
                        return Mono.just(PagerResult.of(1, List.of(room), query));
                    }

                    @Override
                    protected Mono<PagerResult<SaasSpaceInfo>> typePage(QueryParamEntity query) {
                        return Mono.error(new IllegalStateException("type service unavailable"));
                    }
                };
        StepVerifier.create(failed.fetchAll())
                .expectErrorMessage("type service unavailable")
                .verify();
    }

    @Test
    void stablePagesAreBoundedOrderedAndIgnoreCallerContext() {
        var source =
                new SaasSpaceSource() {
                    @Override
                    protected Mono<PagerResult<SaasSpaceInfo>> page(QueryParamEntity query) {
                        assertEquals(200, query.getPageSize());
                        assertEquals("id", query.getSorts().get(0).getName());
                        return Mono.deferContextual(
                                context -> {
                                    assertFalse(context.hasKey("caller-project"));
                                    var data = new ArrayList<SaasSpaceInfo>();
                                    for (int i = query.getPageIndex() * 200;
                                            i < Math.min(401, (query.getPageIndex() + 1) * 200);
                                            i++)
                                        data.add(
                                                SpaceSnapshotTest.node(
                                                        String.format("%04d", i), null, "空间"));
                                    return Mono.just(PagerResult.of(401, data, query));
                                });
                    }
                };
        StepVerifier.create(source.fetchAll().contextWrite(Context.of("caller-project", "limited")))
                .assertNext(
                        values -> {
                            assertEquals(401, values.size());
                            assertEquals("0400", values.get(400).getId());
                        })
                .verifyComplete();
    }

    @Test
    void duplicateTruncatedChangedTotalInvalidAndOversizedPagesFailVisibly() {
        for (int mode = 0; mode < 6; mode++) {
            final int scenario = mode;
            var source =
                    new SaasSpaceSource() {
                        @Override
                        protected Mono<PagerResult<SaasSpaceInfo>> page(QueryParamEntity query) {
                            if (scenario == 5) return Mono.empty();
                            if (scenario == 4)
                                return Mono.just(PagerResult.of(20001, List.of(), query));
                            var data = new ArrayList<SaasSpaceInfo>();
                            for (int i = 0; i < (scenario == 1 ? 199 : 200); i++)
                                data.add(
                                        SpaceSnapshotTest.node(
                                                String.format(
                                                        "%04d", query.getPageIndex() * 200 + i),
                                                null,
                                                "空间"));
                            if (scenario == 0) data.set(1, data.get(0));
                            if (scenario == 3) data.get(0).setId(" ");
                            return Mono.just(
                                    PagerResult.of(
                                            query.getPageIndex() > 0 ? 402 : 401, data, query));
                        }
                    };
            StepVerifier.create(source.fetchAll()).expectError().verify();
        }
    }
}
