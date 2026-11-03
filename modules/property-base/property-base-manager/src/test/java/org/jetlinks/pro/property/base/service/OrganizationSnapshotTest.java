package org.jetlinks.pro.property.base.service;

import org.jetlinks.pro.property.base.entity.PropertyOrganizationEntity;
import org.jetlinks.pro.property.base.organization.OrganizationType;
import org.jetlinks.sdk.server.auth.OrganizationInfo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrganizationSnapshotTest {
    private OrganizationInfo node(String id, String parent, String name) {
        var node = new OrganizationInfo();
        node.setId(id);
        node.setParentId(parent);
        node.setName(name);
        return node;
    }

    @Test
    void flattensCompleteTreeAndKeepsSourceIdentity() {
        var company = node("company", null, "重庆公司");
        company.setChildren(List.of(node("park", "company", "5G园区")));
        var snapshot = OrganizationSnapshot.from(List.of(company));
        assertEquals(2, snapshot.size());
        assertEquals("company", snapshot.get("park").getParentId());
    }

    @Test
    void rejectsDuplicateOrOrphanOrCyclicSnapshotsBeforeWriting() {
        assertThrows(
                RuntimeException.class,
                () ->
                        OrganizationSnapshot.from(
                                List.of(node("a", null, "公司"), node("a", null, "重复"))));
        assertThrows(
                RuntimeException.class,
                () -> OrganizationSnapshot.from(List.of(node("a", "missing", "园区"))));
        assertThrows(
                RuntimeException.class,
                () -> OrganizationSnapshot.from(List.of(node("a", "b", "A"), node("b", "a", "B"))));
    }

    @Test
    void sourcePatchNeverContainsPropertyAnnotation() {
        var snapshot = OrganizationSnapshot.from(List.of(node("park", null, "新名字")));
        var old = new PropertyOrganizationEntity();
        old.setId("park");
        old.setName("原名");
        old.setOrganizationType(OrganizationType.project);
        old.setVersion(7L);
        var patch = snapshot.get("park");
        assertNull(patch.getOrganizationType());
        assertNull(patch.getVersion());
        assertTrue(OrganizationSnapshot.changed(old, patch));
        assertEquals(OrganizationType.project, old.getOrganizationType());
        assertEquals(7L, old.getVersion());
    }

    @Test
    void recognizesRepeatSyncAndDeletedRecordRestoration() {
        var record = OrganizationSnapshot.from(List.of(node("park", null, "园区"))).get("park");
        record.setDeleted(false);
        var incoming = OrganizationSnapshot.from(List.of(node("park", null, "园区"))).get("park");
        assertFalse(OrganizationSnapshot.changed(record, incoming));
        record.setDeleted(true);
        assertTrue(OrganizationSnapshot.changed(record, incoming));
    }
}
