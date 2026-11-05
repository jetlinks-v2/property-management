package org.jetlinks.pro.smartpark.handover.excel;

import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import org.jetlinks.pro.smartpark.handover.api.ProblemRequest;
import org.jetlinks.pro.smartpark.handover.entity.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.function.Function;

/** 只做数据转换和精确匹配，不发请求、不写库；重复名称必须由用户消歧。 */
public final class HandoverImportMapper {
    public record Prepared(int row, Object value, List<HandoverWorkbook.EmbeddedImage> images) {}

    private HandoverImportMapper() {}

    public static Prepared map(
            String kind,
            String projectId,
            String source,
            String stage,
            HandoverWorkbook.ImportRow input,
            HandoverImportCatalog.Catalog catalog) {
        Map<String, String> row = input.values();
        String category =
                resolve(
                        row.get("categoryName"),
                        catalog.categories().stream()
                                .filter(c -> !Boolean.FALSE.equals(c.getEnabled()))
                                .toList(),
                        catalog::categoryLabel,
                        HandoverCategoryEntity::getId,
                        input.row(),
                        "资料分类");
        Map<String, String> properties = new LinkedHashMap<>();
        row.forEach(
                (key, value) -> {
                    if (key.startsWith("properties.") && !value.isBlank())
                        properties.put(key.substring(11), value);
                });
        Object value;
        switch (kind) {
            case "category" -> {
                var entity = new HandoverCategoryEntity();
                entity.setName(row.get("name"));
                entity.setDescription(row.get("description"));
                entity.setEnabled(true);
                entity.setSortIndex(
                        row.getOrDefault("sortIndex", "").isBlank()
                                ? 0
                                : integer(row.get("sortIndex"), input.row(), "排序"));
                entity.setParentId(
                        resolve(
                                row.get("parentName"),
                                catalog.categories().stream()
                                        .filter(c -> c.getParentId() == null)
                                        .toList(),
                                HandoverCategoryEntity::getName,
                                HandoverCategoryEntity::getId,
                                input.row(),
                                "上级分类"));
                value = entity;
            }
            case "ledger" -> {
                var entity = new HandoverLedgerEntity();
                entity.setTitle(row.get("title"));
                entity.setCategoryId(category);
                entity.setOrganizationId(resolve(row.get("organizationName"), catalog.organizations(),
                        HandoverResponsibilityEntity::getName, HandoverResponsibilityEntity::getOrganizationId, input.row(), "责任单位"));
                entity.setEntryUserId(resolve(row.get("entryUserName"), catalog.users(),
                        PropertyEmployeeInfo::name, PropertyEmployeeInfo::id, input.row(), "录入人"));
                entity.setLocation(row.get("location"));
                entity.setDescription(row.get("description"));
                entity.setProposedSolution(row.get("proposedSolution"));
                entity.setRectificationOpinion(row.get("rectificationOpinion"));
                entity.setRemark(row.get("remark"));
                value = entity;
            }
            case "equipment" -> {
                var entity = new HandoverEquipmentEntity();
                entity.setName(row.get("name"));
                entity.setCode(row.get("code"));
                entity.setCategoryId(category);
                entity.setSpecification(row.get("specification"));
                entity.setManufacturer(row.get("manufacturer"));
                entity.setBrand(row.get("brand"));
                entity.setLocation(row.get("location"));
                entity.setRemark(row.get("remark"));
                entity.setProperties(properties);
                entity.setInstallationDate(date(row.get("installationDate"), input.row(), "安装日期"));
                entity.setCommissionDate(date(row.get("commissionDate"), input.row(), "启用日期"));
                entity.setWarrantyEndDate(date(row.get("warrantyEndDate"), input.row(), "质保期至"));
                value = entity;
            }
            case "record" -> {
                var entity = new HandoverRecordEntity();
                entity.setStage(stage);
                entity.setTitle(row.get("title"));
                entity.setCategoryId(category);
                entity.setDescription(row.get("description"));
                entity.setParticipantNames(row.get("participantNames"));
                entity.setRemark(row.get("remark"));
                entity.setTime(date(row.get("time"), input.row(), "业务时间"));
                entity.setProperties(properties);
                String names = row.getOrDefault("personNames", "");
                entity.setPersonIds(
                        names.isBlank()
                                ? List.of()
                                : Arrays.stream(names.split("[;；]"))
                                        .map(String::trim)
                                        .filter(s -> !s.isBlank())
                                        .map(
                                                name ->
                                                        resolve(
                                                                name,
                                                                catalog.users(),
                                                                PropertyEmployeeInfo::name,
                                                                PropertyEmployeeInfo::id,
                                                                input.row(),
                                                                "参与人员"))
                                        .distinct()
                                        .toList());
                value = entity;
            }
            case "item" -> {
                var entity = new HandoverItemEntity();
                entity.setSource(source);
                entity.setName(row.get("name"));
                entity.setCode(row.get("code"));
                entity.setSpecification(row.get("specification"));
                entity.setStandard(row.get("standard"));
                entity.setUnit(row.get("unit"));
                entity.setRemark(row.get("remark"));
                if (!row.getOrDefault("quantity", "").isBlank()) {
                    try {
                        entity.setQuantity(new BigDecimal(row.get("quantity")));
                    } catch (NumberFormatException error) {
                        throw HandoverWorkbook.invalid(input.row(), "数量", "请输入有效非负数");
                    }
                    if (entity.getQuantity().signum() < 0)
                        throw HandoverWorkbook.invalid(input.row(), "数量", "数量不能小于0");
                }
                value = entity;
            }
            case "problem" -> {
                var request = new ProblemRequest();
                request.setProjectId(projectId);
                request.setSource(source);
                request.setClientRequestId("excel-" + UUID.randomUUID());
                request.setDescription(row.get("description"));
                request.setLocation(row.get("location"));
                request.setCategoryId(category);
                request.setOrganizationId(
                        resolve(
                                row.get("organizationName"),
                                catalog.organizations().stream()
                                        .filter(o -> Boolean.TRUE.equals(o.getEnabled()))
                                        .toList(),
                                HandoverResponsibilityEntity::getName,
                                HandoverResponsibilityEntity::getOrganizationId,
                                input.row(),
                                "责任单位"));
                request.setInspectionTime(date(row.get("inspectionTime"), input.row(), "查验时间"));
                request.setInitiatorId(
                        resolve(
                                row.get("initiatorName"),
                                catalog.users(),
                                PropertyEmployeeInfo::name,
                                PropertyEmployeeInfo::id,
                                input.row(),
                                "发起人"));
                String type = row.getOrDefault("inspectionType", "");
                if (!type.isBlank() && !Set.of("承接查验", "管理权移交").contains(type))
                    throw HandoverWorkbook.invalid(input.row(), "查验类别", "请从下拉选项选择");
                request.setInspectionType("管理权移交".equals(type) ? "management" : "handover");
                if (!"SITE_INSPECTION".equals(source)) {
                    String code = row.getOrDefault("itemCode", "");
                    var candidates =
                            catalog.items().stream()
                                    .filter(i -> !Boolean.TRUE.equals(i.getConfirmed()))
                                    .filter(i -> code.isBlank() || code.equals(i.getCode()))
                                    .toList();
                    request.setItemId(
                            resolveRequired(
                                    row.get("itemName"),
                                    candidates,
                                    HandoverItemEntity::getName,
                                    HandoverItemEntity::getId,
                                    input.row(),
                                    "清单名称"));
                }
                value = request;
            }
            default -> throw HandoverWorkbook.invalid(input.row(), "类型", "不支持的导入类型");
        }
        if (value instanceof HandoverEntity entity) entity.setProjectId(projectId);
        return new Prepared(input.row(), value, input.images());
    }

    private static <E> String resolveRequired(
            String name,
            List<E> rows,
            Function<E, String> label,
            Function<E, String> id,
            int row,
            String column) {
        if (name == null || name.isBlank()) throw HandoverWorkbook.invalid(row, column, "必填项不能为空");
        return resolve(name, rows, label, id, row, column);
    }

    private static <E> String resolve(
            String name,
            List<E> rows,
            Function<E, String> label,
            Function<E, String> id,
            int row,
            String column) {
        if (name == null || name.isBlank()) return null;
        var matches = rows.stream().filter(item -> name.equals(label.apply(item))).toList();
        if (matches.size() != 1)
            throw HandoverWorkbook.invalid(
                    row, column, matches.isEmpty() ? "当前项目找不到该选项" : "存在多个同名选项，请先消歧或补充编号");
        return id.apply(matches.get(0));
    }

    private static Integer integer(String text, int row, String column) {
        try {
            int value = Integer.parseInt(text);
            if (value < 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException error) {
            throw HandoverWorkbook.invalid(row, column, "请输入非负整数");
        }
    }

    private static Long date(String text, int row, String column) {
        if (text == null || text.isBlank()) return null;
        try {
            var zone = ZoneId.of("Asia/Shanghai");
            return text.length() == 10
                    ? LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli()
                    : LocalDateTime.parse(
                                    text,
                                    DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
                                            .withResolverStyle(ResolverStyle.STRICT))
                            .atZone(zone)
                            .toInstant()
                            .toEpochMilli();
        } catch (RuntimeException error) {
            throw HandoverWorkbook.invalid(row, column, "日期格式应为yyyy-MM-dd或yyyy-MM-dd HH:mm:ss");
        }
    }
}
