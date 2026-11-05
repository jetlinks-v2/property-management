package org.jetlinks.pro.property.decoration.excel;

import org.hswebframework.web.bean.FastBeanCopier;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.property.base.dictionary.PropertyDictionaryItemInfo;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.property.decoration.model.DecorationModels;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Function;

import static org.jetlinks.pro.property.decoration.excel.DecorationCheckWorkbook.normalize;
import static org.jetlinks.pro.property.decoration.excel.DecorationCheckWorkbook.problem;

/** 主档解析与合并计划。输入目录必须来自服务端有权项目和启用字典，不接收浏览器提供的映射。 */
public final class DecorationCheckImportPlan {
    private DecorationCheckImportPlan() {}
    public record Dimension(String projectId, String houseType, String decorationType, String scene) {}
    public record Group(Dimension dimension, List<DecorationCheckWorkbook.Entry> rows) {}
    public record Plan(int totalCount, int validCount, List<Group> groups, List<DecorationCheckWorkbook.Problem> errors) {}

    public static Plan resolve(DecorationCheckWorkbook.Parsed parsed, List<PropertyOrganizationInfo> projects,
                               List<PropertyDictionaryItemInfo> houses) {
        var errors = new ArrayList<>(parsed.errors());
        var grouped = new LinkedHashMap<Dimension, List<DecorationCheckWorkbook.Entry>>();
        var names = new HashMap<List<String>, Integer>();
        var orders = new HashMap<List<String>, Integer>();
        for (var row : parsed.entries()) {
            var projectIds = projects.stream().filter(p -> normalize(p.getName()).equals(row.projectName()))
                .map(PropertyOrganizationInfo::getId).distinct().toList();
            var houseTypes = houses.stream().filter(h -> normalize(h.value()).equals(row.houseType()) || normalize(h.text()).equals(row.houseType()))
                .map(PropertyDictionaryItemInfo::value).distinct().toList();
            if (projectIds.size() != 1) problem(errors, row.rowNumber(), 1, row.projectName(), projectIds.isEmpty() ? "project" : "ambiguous_project");
            if (houseTypes.size() != 1) problem(errors, row.rowNumber(), 2, row.houseType(), houseTypes.isEmpty() ? "house_type" : "ambiguous_house_type");
            if (projectIds.size() != 1 || houseTypes.size() != 1) continue;
            var dimension = new Dimension(projectIds.get(0), houseTypes.get(0), row.decorationType(), row.scene());
            var category = List.of(dimension.projectId(), dimension.houseType(), dimension.decorationType(), dimension.scene(), row.item().getCategory());
            var item = new ArrayList<>(category); item.add(row.item().getName());
            // 同一字典的编码和文案可以混填；归一后必须再检查重复和分类排序冲突。
            if (names.putIfAbsent(item, row.rowNumber()) != null) problem(errors, row.rowNumber(), 6, row.item().getName(), "duplicate_item");
            if (row.categorySort() != null) {
                var prior = orders.putIfAbsent(category, row.categorySort());
                if (prior != null && !prior.equals(row.categorySort())) problem(errors, row.rowNumber(), 5, row.categorySort().toString(), "category_sort");
            }
            grouped.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(row);
        }
        grouped.values().stream().filter(rows -> rows.size() > 200).forEach(rows -> problem(errors, rows.get(0).rowNumber(), 6, "", "check_count"));
        var unique = new java.util.LinkedHashSet<>(errors); errors.clear(); errors.addAll(unique);
        var invalid = errors.stream().map(DecorationCheckWorkbook.Problem::rowNumber).collect(java.util.stream.Collectors.toSet());
        int valid = (int) parsed.entries().stream().filter(row -> !invalid.contains(row.rowNumber())).count();
        var groups = errors.isEmpty() ? grouped.entrySet().stream().map(entry -> new Group(entry.getKey(), entry.getValue())).toList() : List.<Group>of();
        return new Plan(parsed.entries().size(), valid, groups, errors);
    }

    /** 同分类/同名小项更新并保留ID，其他项不删除；空排序沿用现值或追加，图片回执由编排层提供。 */
    public static List<DecorationModels.CheckItem> merge(List<DecorationModels.CheckItem> current, Group group,
                                                       Function<DecorationCheckWorkbook.Entry, String> imageReceipts) {
        var result = current.stream().map(item -> FastBeanCopier.copy(item, new DecorationModels.CheckItem())).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        var categories = new LinkedHashMap<String, Integer>();
        int categoryMax = result.stream().map(DecorationModels.CheckItem::getCategorySort).filter(java.util.Objects::nonNull).mapToInt(Integer::intValue).max().orElse(0);
        for (var item : result) {
            var category = normalize(item.getCategory());
            item.setCategory(category);
            if (!categories.containsKey(category)) categories.put(category, item.getCategorySort() == null ? ++categoryMax : item.getCategorySort());
        }
        for (var row : group.rows()) if (row.categorySort() != null) categories.put(row.item().getCategory(), row.categorySort());
        categoryMax = categories.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        for (var row : group.rows()) {
            String category = row.item().getCategory();
            if (!categories.containsKey(category)) categories.put(category, ++categoryMax);
            var existing = result.stream().filter(item -> normalize(item.getCategory()).equals(category) && normalize(item.getName()).equals(row.item().getName())).findFirst();
            var updated = FastBeanCopier.copy(row.item(), new DecorationModels.CheckItem());
            updated.setId(existing.map(DecorationModels.CheckItem::getId).orElseGet(IDGenerator.MD5::generate));
            int nextSort = result.stream().filter(item -> normalize(item.getCategory()).equals(category)).mapToInt(DecorationModels.CheckItem::getSort).max().orElse(0) + 1;
            updated.setSort(row.itemSort() == null ? existing.map(DecorationModels.CheckItem::getSort).orElse(nextSort) : row.itemSort());
            updated.setExampleFileId(row.image() == null ? null : imageReceipts.apply(row));
            existing.ifPresent(result::remove); result.add(updated);
        }
        for (var item : result) item.setCategorySort(categories.get(normalize(item.getCategory())));
        org.jetlinks.pro.property.decoration.service.DecorationRules.sortChecks(result);
        return result;
    }
}
