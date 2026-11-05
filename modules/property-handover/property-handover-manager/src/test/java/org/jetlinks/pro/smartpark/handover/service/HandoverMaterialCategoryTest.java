package org.jetlinks.pro.smartpark.handover.service;

import static org.jetlinks.pro.smartpark.handover.service.HandoverPersistenceFixture.*;
import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.pro.smartpark.handover.web.HandoverCategoryController;
import org.jetlinks.pro.smartpark.handover.entity.HandoverMaterialCategoryEntity;
import org.hswebframework.web.exception.BusinessException;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Set;

/** 资料分类是应用级共享目录，查询不应要求先选择一个项目。 */
@SpringBootTest(classes = HandoverPersistenceFixture.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import(HandoverCategoryController.class)
class HandoverMaterialCategoryTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        HandoverPersistenceFixture.database(registry);
    }

    @Autowired HandoverCategoryController controller;
    @Autowired HandoverDirectory directory;
    @Autowired HandoverMaterialCategoryRepository materials;
    @Autowired HandoverScope scope;
    @Autowired HandoverRecordRepository records;
    @Autowired HandoverMaterialCategoryService service;

    @BeforeEach
    void prepare() {
        HandoverPersistenceFixture.directory(directory, true, List.of("external-a"));
        records.createDelete().where("tenantId", "tenant").execute().block(WAIT);
        materials.createDelete().where("tenantId", "tenant").execute().block(WAIT);
    }

    @Test
    void listMaterialCategoriesWithoutSelectingProject() {
        var page = controller.query(new HandoverQuery())
                .contextWrite(access("tester", Set.of("project")))
                .block(WAIT);
        assertNotNull(page);
        assertEquals(0, page.getTotal());
    }
    private HandoverMaterialCategoryEntity save(String name, String parentId) {
        var input = new HandoverMaterialCategoryEntity();
        input.setName(name);
        input.setParentId(parentId);
        return controller.save(input).contextWrite(access("tester", Set.of())).block(WAIT);
    }

    @Test
    void applicationCatalogHasTwoLevelsAndRetainsSelectionAfterRename() {
        var root = save("工程资料", null);
        var child = save("竣工图纸", root.getId());
        var tree = controller.tree().contextWrite(access("tester", Set.of())).collectList().block(WAIT);
        assertEquals(1, tree.size());
        assertEquals(child.getId(), tree.get(0).getChildren().get(0).getId());
        assertThrows(BusinessException.class, () -> save("不允许三级", child.getId()));
        child.setName("建筑竣工图");
        var updated = controller.save(child).contextWrite(access("tester", Set.of())).block(WAIT);
        assertEquals(1, updated.getVersion());
        assertEquals("工程资料", controller.read(child.getId())
                .contextWrite(access("tester", Set.of())).block(WAIT).getParentName());
        assertThrows(BusinessException.class, () -> controller.delete(root.getId(), 0)
                .contextWrite(access("tester", Set.of())).block(WAIT));
    }

    @Test
    void staleUpdatesFailAndLogicalDeletionReleasesNameOnly() {
        var row = save("设计资料", null);
        var stale = controller.read(row.getId()).contextWrite(access("tester", Set.of())).block(WAIT);
        row.setDescription("第一次修改");
        controller.save(row).contextWrite(access("tester", Set.of())).block(WAIT);
        stale.setDescription("过期修改");
        assertThrows(BusinessException.class, () -> controller.save(stale)
                .contextWrite(access("tester", Set.of())).block(WAIT));
        assertThrows(BusinessException.class, () -> save("设计资料", null));
        controller.delete(row.getId(), 1).contextWrite(access("tester", Set.of())).block(WAIT);
        assertTrue(materials.findById(row.getId()).block(WAIT).getDeleted());
        assertNotEquals(row.getId(), save("设计资料", null).getId());
    }

    @Test
    void projectParameterCannotExposeOtherApplicationDataOrBypassEmployeeMaintenance() {
        var row = save("仅当前应用", null);
        var query = new HandoverQuery();
        query.setProjectId("unrelated-project");
        assertEquals(1, controller.query(query).contextWrite(access("tester", Set.of())).block(WAIT).getTotal());
        var foreign = new HandoverMaterialCategoryEntity();
        foreign.setId("foreign"); foreign.setName("另一应用资料");
        scope.initialize(foreign, new HandoverContext("tenant", "another-app", "tester", "tester"));
        materials.insert(foreign).block(WAIT);
        assertEquals(1, controller.query(query).contextWrite(access("tester", Set.of())).block(WAIT).getTotal());
        assertThrows(AccessDenyException.class, () -> controller.read("foreign")
                .contextWrite(access("tester", Set.of())).block(WAIT));
        HandoverPersistenceFixture.directory(directory, false, List.of());
        assertThrows(AccessDenyException.class, () -> save("不允许维护", null));
        assertEquals(row.getName(), controller.read(row.getId())
                .contextWrite(access("tester", Set.of())).block(WAIT).getName());
    }

    @Test
    void referencedMaterialCannotBeDeletedAndInactiveCatalogStillHasHistoricalName() {
        var row = save("验收图纸", null);
        var record = new org.jetlinks.pro.smartpark.handover.entity.HandoverRecordEntity();
        record.setId("record-ref"); record.setProjectId("project"); record.setStage("material");
        record.setTitle("既有资料"); record.setCategoryId(row.getId());
        scope.initialize(record, new HandoverContext("tenant", "application", "tester", "tester"));
        records.insert(record).block(WAIT);
        assertThrows(BusinessException.class, () -> controller.delete(row.getId(), row.getVersion())
                .contextWrite(access("tester", Set.of())).block(WAIT));
        assertFalse(materials.findById(row.getId()).block(WAIT).getDeleted());
        row.setEnabled(false);
        controller.save(row).contextWrite(access("tester", Set.of())).block(WAIT);
        assertEquals("验收图纸", controller.read(row.getId()).contextWrite(access("tester", Set.of())).block(WAIT).getName());
        assertThrows(BusinessException.class, () -> service.selectable(row.getId())
                .contextWrite(access("tester", Set.of())).block(WAIT));
    }

}
