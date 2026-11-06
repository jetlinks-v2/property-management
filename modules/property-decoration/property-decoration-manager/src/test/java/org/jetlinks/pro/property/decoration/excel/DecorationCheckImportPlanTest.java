package org.jetlinks.pro.property.decoration.excel;

import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryItemInfo;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecorationCheckImportPlanTest {
    private PropertyOrganizationInfo project(String id, String name) { var p = new PropertyOrganizationInfo(); p.setId(id); p.setName(name); return p; }
    private List<PropertyDictionaryItemInfo> houses() { return List.of(new PropertyDictionaryItemInfo("residential", "住宅", 1L)); }
    private DecorationCheckWorkbook.Entry entry(int row, String house, String name, Integer categorySort, Integer itemSort) {
        var item = new DecorationModels.CheckItem(); item.setCategory("安全"); item.setName(name); item.setInputType("text"); item.setExample("新判断标准");
        return new DecorationCheckWorkbook.Entry(row, "daily", "项目", house, "first", categorySort, itemSort, item, null);
    }
    private DecorationCheckImportPlan.Plan resolve(List<DecorationCheckWorkbook.Entry> rows) {
        return DecorationCheckImportPlan.resolve(new DecorationCheckWorkbook.Parsed(rows, List.of()), List.of(project("p", "项目")), houses());
    }

    @Test void nameResolutionUsesAuthorizedProjectsAndCanonicalDictionaryValues() {
        var plan = resolve(List.of(entry(2, "住宅", "门窗", 1, 1), entry(3, "residential", "护栏", 1, 2)));
        assertTrue(plan.errors().isEmpty()); assertEquals(2, plan.validCount()); assertEquals(1, plan.groups().size());
        assertEquals("p", plan.groups().get(0).dimension().projectId()); assertEquals("residential", plan.groups().get(0).dimension().houseType());
        var duplicate = resolve(List.of(entry(2, "住宅", "门窗", 1, 1), entry(3, "residential", "门窗", 1, 1)));
        assertTrue(duplicate.groups().isEmpty()); assertTrue(duplicate.errors().stream().anyMatch(e -> e.rowNumber() == 3 && e.code().equals("duplicate_item")));
    }

    @Test void unknownOrAmbiguousMasterDataPreventsEveryDimensionFromBeingApplied() {
        var rows = List.of(entry(2, "住宅", "门窗", 1, 1), entry(3, "停用房屋类型", "护栏", 1, 2));
        var unknown = resolve(rows); assertTrue(unknown.groups().isEmpty()); assertEquals(1, unknown.validCount());
        assertTrue(unknown.errors().stream().anyMatch(e -> e.code().equals("house_type")));
        var noProject = DecorationCheckImportPlan.resolve(new DecorationCheckWorkbook.Parsed(rows, List.of()), List.of(), houses());
        assertEquals(0, noProject.validCount()); assertTrue(noProject.groups().isEmpty());
        assertTrue(noProject.errors().stream().anyMatch(e -> e.code().equals("project")));
        var ambiguous = DecorationCheckImportPlan.resolve(new DecorationCheckWorkbook.Parsed(rows, List.of()), List.of(project("one", "项目"), project("two", "项目")), houses());
        assertTrue(ambiguous.errors().stream().anyMatch(e -> e.code().equals("ambiguous_project")));
        var sameText = DecorationCheckImportPlan.resolve(new DecorationCheckWorkbook.Parsed(List.of(rows.get(0)), List.of()), List.of(project("p", "项目")),
            List.of(new PropertyDictionaryItemInfo("one", "住宅", 1L), new PropertyDictionaryItemInfo("two", "住宅", 2L)));
        assertTrue(sameText.errors().stream().anyMatch(e -> e.code().equals("ambiguous_house_type")));
    }

    @Test void mergePreservesIdsUntouchedItemsAndPriorSnapshotsWhileUpdatingImportedRows() {
        var old = new DecorationModels.CheckItem(); old.setId("stable-id"); old.setCategory("安全"); old.setCategorySort(1); old.setName("门窗");
        old.setSort(4); old.setInputType("image"); old.setExampleFileId("old-photo"); old.setStandard("原图片标准");
        var untouched = new DecorationModels.CheckItem(); untouched.setId("untouched"); untouched.setCategory("安全"); untouched.setCategorySort(1); untouched.setName("护栏"); untouched.setSort(8);
        var other = new DecorationModels.CheckItem(); other.setId("other"); other.setCategory("环境"); other.setCategorySort(2); other.setName("环境项"); other.setSort(1);
        var group = resolve(List.of(entry(2, "住宅", "门窗", 3, null), entry(3, "住宅", "新项", 3, null))).groups().get(0);
        var merged = DecorationCheckImportPlan.merge(List.of(old, untouched, other), group, ignored -> { fail("无图片时不得上传"); return null; });
        assertEquals(4, merged.size()); assertEquals("other", merged.get(0).getId());
        var changed = merged.stream().filter(item -> "stable-id".equals(item.getId())).findFirst().orElseThrow();
        assertEquals(4, changed.getSort()); assertEquals("text", changed.getInputType()); assertNull(changed.getExampleFileId()); assertNull(changed.getStandard());
        assertEquals("新判断标准", changed.getExample());
        var added = merged.stream().filter(item -> "新项".equals(item.getName())).findFirst().orElseThrow(); assertNotNull(added.getId()); assertEquals(9, added.getSort());
        assertTrue(merged.stream().filter(item -> "安全".equals(item.getCategory())).allMatch(item -> item.getCategorySort() == 3));
        assertEquals("old-photo", old.getExampleFileId()); assertEquals(1, old.getCategorySort()); assertEquals(1, untouched.getCategorySort());
    }

    @Test void embeddedImagesUsePrivateReceiptAndDistinctDimensionsRemainSeparate() {
        var source = entry(2, "住宅", "图片", null, 2); source.item().setInputType("image"); source.item().setStandard("图片标准");
        var image = new DecorationCheckWorkbook.Entry(2, source.scene(), source.projectName(), source.houseType(), source.decorationType(), null, 2, source.item(), new DecorationCheckWorkbook.Image(new byte[]{1}, "png"));
        var second = new DecorationCheckWorkbook.Entry(3, "secondAcceptance", source.projectName(), source.houseType(), "second", null, 1, source.item(), null);
        var plan = resolve(List.of(image, second)); assertEquals(2, plan.groups().size());
        var merged = DecorationCheckImportPlan.merge(List.of(), plan.groups().get(0), row -> "private-receipt-" + row.rowNumber());
        assertEquals("private-receipt-2", merged.get(0).getExampleFileId()); assertEquals("图片标准", merged.get(0).getStandard()); assertEquals(1, merged.get(0).getCategorySort());
    }

    @Test void parsedErrorsAndTwoHundredItemLimitPreventPartialPlans() {
        var entry = entry(2, "住宅", "门窗", 1, 1);
        var failed = DecorationCheckImportPlan.resolve(new DecorationCheckWorkbook.Parsed(List.of(entry), List.of(new DecorationCheckWorkbook.Problem(2, "填报类型*", "wrong", "choice"))),
            List.of(project("p", "项目")), houses());
        assertTrue(failed.groups().isEmpty()); assertEquals(0, failed.validCount());
        var tooMany = new ArrayList<DecorationCheckWorkbook.Entry>(); for (int index = 0; index < 201; index++) tooMany.add(entry(index + 2, "住宅", "检查项" + index, 1, index + 1));
        var limited = resolve(tooMany); assertTrue(limited.groups().isEmpty()); assertTrue(limited.errors().stream().anyMatch(e -> e.code().equals("check_count")));
    }
}
