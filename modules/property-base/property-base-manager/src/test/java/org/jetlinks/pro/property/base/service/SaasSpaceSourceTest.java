package org.jetlinks.pro.property.base.service;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.pro.property.base.space.SaasSpaceInfo;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SaasSpaceSourceTest {
    @Test
    void rpcListRestoresTypedSourceDtoAndExplicitlyDisablesPaging() {
        var command = SaasSpaceSource.listCommand(new QueryParamEntity().noPaging());
        var node = assertInstanceOf(SaasSpaceInfo.class, command.createResponseData(Map.of("id", "room", "name", "101",
            "parentId", "floor", "sortIndex", 10)));
        assertEquals("room", node.getId());
        assertEquals("floor", node.getParentId());
        assertEquals(10L, node.getSortIndex());
        assertFalse(command.asQueryParam().isPaging());
    }

    @Test
    void unorderedCompleteSnapshotKeepsHierarchyAndIgnoresCallerContext() {
        var source = new SaasSpaceSource() {
            @Override protected Flux<SaasSpaceInfo> spaces(QueryParamEntity query) {
                assertFalse(query.isPaging());
                return Flux.deferContextual(context -> {
                    assertFalse(context.hasKey("caller-project"));
                    var room = SpaceSnapshotTest.node("a-room", "z-root", "101");
                    room.setSpaceTypeId("room-type");
                    return Flux.just(SpaceSnapshotTest.node("z-root", null, "园区"), room);
                });
            }
            @Override protected Flux<SaasSpaceInfo> types(QueryParamEntity query) {
                assertFalse(query.isPaging());
                return Flux.just(SpaceSnapshotTest.node("room-type", null, "房间"));
            }
        };
        StepVerifier.create(source.fetchAll().contextWrite(Context.of("caller-project", "limited")))
            .assertNext(rows -> {
                assertEquals(2, rows.size());
                var tree = SpaceSnapshot.from(rows);
                assertEquals("z-root", tree.get("a-room").getRootId());
                assertEquals("房间", tree.get("a-room").getSpaceTypeName());
            }).verifyComplete();
    }

    @Test
    void duplicateInvalidOversizedAndInterruptedSnapshotsFailVisibly() {
        for (int scenario = 0; scenario < 4; scenario++) {
            final int mode = scenario;
            var source = new SaasSpaceSource() {
                @Override protected Flux<SaasSpaceInfo> spaces(QueryParamEntity query) {
                    var row = SpaceSnapshotTest.node("room", null, "101");
                    if (mode == 0) return Flux.just(row, row);
                    if (mode == 1) { row.setId(" "); return Flux.just(row); }
                    if (mode == 2) return Flux.range(0, 20_001)
                        .map(i -> SpaceSnapshotTest.node("room-" + i, null, "101"));
                    return Flux.concat(Flux.just(row), Flux.error(new IllegalStateException("source failed")));
                }
            };
            StepVerifier.create(source.fetchAll()).expectError().verify();
        }
    }

    @Test
    void typeFailureOrMissingReferenceRejectsWholeSnapshot() {
        for (boolean missing : new boolean[]{false, true}) {
            var source = new SaasSpaceSource() {
                @Override protected Flux<SaasSpaceInfo> spaces(QueryParamEntity query) {
                    var row = SpaceSnapshotTest.node("room", null, "101");
                    row.setSpaceTypeId("room-type");
                    return Flux.just(row);
                }
                @Override protected Flux<SaasSpaceInfo> types(QueryParamEntity query) {
                    return missing ? Flux.empty() : Flux.error(new IllegalStateException("types unavailable"));
                }
            };
            StepVerifier.create(source.fetchAll()).expectError().verify();
        }
    }
}
