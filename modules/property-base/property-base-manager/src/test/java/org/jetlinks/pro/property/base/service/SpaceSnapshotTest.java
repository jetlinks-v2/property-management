package org.jetlinks.pro.property.base.service;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.property.base.space.SaasSpaceInfo;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class SpaceSnapshotTest {
    @Test
    void computesActualRootPathAndLeafAcrossUnorderedFullForest() {
        var tree =
                SpaceSnapshot.from(
                        List.of(
                                node("room", "floor", "101"), node("alone", null, "独栋"),
                                node("floor", "root", "一层"), node("root", null, "一栋")));
        assertEquals("root", tree.get("room").getRootId());
        assertEquals("一栋/一层/101", tree.get("room").getFullName());
        assertTrue(tree.get("room").getLeaf());
        assertTrue(tree.get("alone").getLeaf());
        assertFalse(tree.get("root").getLeaf());
        assertFalse(tree.get("floor").getLeaf());
        assertEquals("alone", tree.get("alone").getRootId());
        assertEquals("独栋", tree.get("alone").getFullName());
    }

    @Test
    void typeNamesAnnotateArbitraryDepthAndRenamesAffectDescendantLocation() {
        var building = node("building", null, "1栋");
        building.setSpaceTypeId("building-type");
        building.setSpaceTypeName("楼栋");
        var room = node("room", "building", "101");
        room.setSpaceTypeId("room-type");
        room.setSpaceTypeName("房间");
        var before = SpaceSnapshot.from(List.of(building, room));
        assertEquals("楼栋：1栋 / 房间：101", before.get("room").getSpaceLocation());
        assertEquals("room-type", before.get("room").getSpaceTypeId());
        building.setSpaceTypeName("建筑");
        var after = SpaceSnapshot.from(List.of(building, room));
        assertTrue(SpaceSnapshot.changed(before.get("room"), after.get("room")));
        assertEquals("建筑：1栋 / 房间：101", after.get("room").getSpaceLocation());
        assertTrue(after.get("room").getLeaf());
        assertFalse(after.get("building").getLeaf());
        room.setParentId(null);
        room.setSpaceTypeName(null);
        assertEquals(
                "room-type：101", SpaceSnapshot.from(List.of(room)).get("room").getSpaceLocation());
    }

    @Test
    void rejectsDuplicateMissingParentCyclesAndInvalidIdentifiersBeforeWriting() {
        for (var nodes :
                List.of(
                        List.of(node("a", null, "甲"), node("a", null, "乙")),
                        List.of(node("a", "missing", "甲")),
                        List.of(node("a", "a", "甲")),
                        List.of(node("a", "b", "甲"), node("b", "a", "乙")),
                        List.of(node("bad/id", null, "甲")),
                        List.of(node("a", null, " ")))) {
            assertThrows(
                    org.hswebframework.web.exception.BusinessException.class,
                    () -> SpaceSnapshot.from(nodes));
        }
    }

    @Test
    void rejectsOversizedDeepOrOverlongPaths() {
        var tooMany = new ArrayList<SaasSpaceInfo>();
        for (int i = 0; i <= SpaceSnapshot.MAX_NODES; i++) tooMany.add(node("n" + i, null, "空间"));
        assertThrows(RuntimeException.class, () -> SpaceSnapshot.from(tooMany));
        var deep = new ArrayList<SaasSpaceInfo>();
        for (int i = 0; i < 129; i++) deep.add(node("n" + i, i == 0 ? null : "n" + (i - 1), "空间"));
        assertThrows(RuntimeException.class, () -> SpaceSnapshot.from(deep));
        var longPath = new ArrayList<SaasSpaceInfo>();
        for (int i = 0; i < 33; i++)
            longPath.add(node("n" + i, i == 0 ? null : "n" + (i - 1), "名".repeat(128)));
        assertThrows(RuntimeException.class, () -> SpaceSnapshot.from(longPath));
    }

    static SaasSpaceInfo node(String id, String parent, String name) {
        var node = new SaasSpaceInfo();
        node.setId(id);
        node.setParentId(parent);
        node.setName(name);
        return node;
    }
}
