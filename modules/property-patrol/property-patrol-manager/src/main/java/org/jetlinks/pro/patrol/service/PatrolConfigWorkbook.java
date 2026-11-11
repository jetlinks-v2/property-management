package org.jetlinks.pro.patrol.service;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.asset.PatrolAssetType;
import org.jetlinks.pro.patrol.domain.PatrolRule;
import org.jetlinks.pro.patrol.entity.PatrolConfigEntity;
import org.jetlinks.pro.patrol.entity.PatrolLevelEntity;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.ArrayList;
import java.util.TreeMap;

/** 配置导出只写当前可读字段；名称按字面文本写入，不扩展关联人员或附件权限。 */
public final class PatrolConfigWorkbook {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public static byte[] write(PatrolAssetType type, List<? extends PatrolConfigEntity> rows) throws IOException {
        long detailCount = rows.stream().mapToLong(entity -> entity instanceof PatrolStandardEntity standard ? standard.getItems().size()
                : entity instanceof PatrolLevelEntity level ? ruleRows(level.getRule()) : 0).sum();
        if (detailCount > 100000) throw new BusinessException("error.patrol.config.export_detail_limit");
        try (var book = new SXSSFWorkbook(100); var out = new ByteArrayOutputStream()) {
            var sheet = book.createSheet(type.getName());
            boolean level = type == PatrolAssetType.patrolLevel, standard = type == PatrolAssetType.patrolStandard;
            headers(sheet, level ? new String[]{"点位等级", "状态", "编辑人", "最后编辑时间", "备注", "所属项目", "巡更频次"}
                    : standard ? new String[]{"标准名称", "检查项数量", "状态", "最后编辑时间", "备注", "所属项目", "检查项目"}
                    : new String[]{"标签名称", "状态", "最后编辑时间", "备注", "所属项目"});
            Sheet rules = level ? book.createSheet("巡更规则") : null;
            if (rules != null) headers(rules, new String[]{"点位等级", "星期或高级日期", "时段序号", "开始", "结束", "跨天或全天", "巡更频率（分钟）", "每次频率应巡次数", "最小间隔（分钟）", "所属项目"});
            Sheet items = standard ? book.createSheet("检查项目") : null;
            if (items != null) headers(items, new String[]{"标准名称", "序号", "检查项名称", "检查项类型", "必填", "必拍", "选项内容", "合格选项", "数值下限", "数值上限", "参考说明", "所属项目"});
            if (standard) sheet.setColumnWidth(6, 70 * 256);
            for (var entity : rows) {
                String time = entity.getModifyTime() == null ? "" : TIME.format(Instant.ofEpochMilli(entity.getModifyTime()));
                String status = entity.isEnabled() ? "启用" : "停用";
                if (level) {
                    var rule = ((PatrolLevelEntity) entity).getRule();
                    values(sheet, entity.getName(), status, entity.getModifierName(), time, entity.getDescription(),
                            entity.getProjectName(), summary(((PatrolLevelEntity) entity).getRuleSummary()));
                    if (rule != null) {
                        if (rule.weekly() != null) new TreeMap<>(rule.weekly()).forEach((day, windows) ->
                                ruleValues(rules, entity.getName(), "星期" + "一二三四五六日".charAt(day - 1), windows, rule, entity.getProjectName()));
                        if (rule.dates() != null) new TreeMap<>(rule.dates()).forEach((date, windows) ->
                                ruleValues(rules, entity.getName(), date.toString(), windows, rule, entity.getProjectName()));
                    }
                }
                else if (standard) {
                    var checkItems = ((PatrolStandardEntity) entity).getItems();
                    var descriptions = new ArrayList<String>();
                    for (int i = 0; i < checkItems.size(); i++) descriptions.add(itemDescription(checkItems.get(i), i + 1));
                    values(sheet, entity.getName(), checkItems.size(), status, time, entity.getDescription(),
                            entity.getProjectName(), summary(descriptions));
                    for (int i = 0; i < checkItems.size(); i++) {
                        var item = checkItems.get(i);
                        values(items, entity.getName(), i + 1, item.title(), itemType(item), item.required() ? "是" : "否", item.photoRequired() ? "是" : "否",
                                item.type() == PatrolStandardEntity.ItemType.choice ? String.join("、", item.options()) : "",
                                item.type() == PatrolStandardEntity.ItemType.choice ? String.join("、", item.qualifiedOptions()) : "",
                                item.type() == PatrolStandardEntity.ItemType.number ? item.lowerLimit() : null,
                                item.type() == PatrolStandardEntity.ItemType.number ? item.upperLimit() : null,
                                item.referenceText(), entity.getProjectName());
                    }
                } else values(sheet, entity.getName(), status, time, entity.getDescription(), entity.getProjectName());
            }
            book.write(out); return out.toByteArray();
        }
    }

    private static void headers(Sheet sheet, String[] labels) {
        values(sheet, (Object[]) labels); sheet.createFreezePane(0, 1);
        for (int i = 0; i < labels.length; i++) sheet.setColumnWidth(i, 24 * 256);
    }
    private static void values(Sheet sheet, Object... values) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        int lines = 1;
        for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i);
            // 精确数值上下限按十进制文本导出，避免 Excel 浮点精度损失；只有计数写数字。
            if (values[i] instanceof Integer count) cell.setCellValue(count);
            else cell.setCellValue(values[i] == null ? "" : values[i].toString());
            if (values[i] instanceof String text && text.contains("\n")) {
                cell.setCellStyle(wrappedStyle(sheet));
                lines = Math.max(lines, (int) text.chars().filter(character -> character == '\n').count() + 1);
            }
        }
        if (lines > 1) row.setHeightInPoints(Math.min(409, lines * 15f));
    }
    private static org.apache.poi.ss.usermodel.CellStyle wrappedStyle(Sheet sheet) {
        var book = sheet.getWorkbook();
        // 一个工作簿复用同一换行样式，避免大批导出创建成千上万份单元格样式。
        if (book.getNumCellStyles() > 1) return book.getCellStyleAt(1);
        var style = book.createCellStyle(); style.setWrapText(true);
        style.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.TOP);
        return style;
    }
    /** 每项独立编号并标明字段；只展示该类型实际适用的配置，不输出隐藏的遗留规则。 */
    private static String itemDescription(PatrolStandardEntity.CheckItem item, int number) {
        var lines = new ArrayList<String>();
        lines.add(number + "、检查项名称：" + item.title());
        lines.add("检查项类型：" + itemType(item));
        if (item.type() == PatrolStandardEntity.ItemType.number) {
            lines.add("数值下限：" + (item.lowerLimit() == null ? "未设置" : item.lowerLimit().toPlainString()));
            lines.add("数值上限：" + (item.upperLimit() == null ? "未设置" : item.upperLimit().toPlainString()));
        } else if (item.type() == PatrolStandardEntity.ItemType.choice) {
            lines.add("选项内容：" + String.join("、", item.options()));
            lines.add("合格选项：" + String.join("、", item.qualifiedOptions()));
        }
        lines.add("必填：" + (item.required() ? "是" : "否"));
        lines.add("必拍：" + (item.photoRequired() ? "是" : "否"));
        if (item.referenceText() != null && !item.referenceText().isBlank()) lines.add("参考说明：" + item.referenceText());
        return String.join("\n", lines) + "\n";
    }
    private static long ruleRows(PatrolRule rule) {
        if (rule == null) return 0;
        return (rule.weekly() == null ? 0 : rule.weekly().values().stream().mapToLong(w -> Math.max(1, w.size())).sum())
                + (rule.dates() == null ? 0 : rule.dates().values().stream().mapToLong(w -> Math.max(1, w.size())).sum());
    }
    private static void ruleValues(Sheet sheet, String name, String label, List<PatrolRule.Window> windows, PatrolRule rule, String projectName) {
        if (windows.isEmpty()) { values(sheet, name, label, "不巡更", "", "", "", "", "", "", projectName); return; }
        for (int i = 0; i < windows.size(); i++) {
            var window = windows.get(i);
            values(sheet, name, label, i + 1, clock(window.startMinute()), clock(window.endMinute()),
                    window.endMinute() < window.startMinute() ? "次日" : window.endMinute() == window.startMinute() ? "全天" : "",
                    window.intervalMinutes(), window.requiredCount(), window.cooldown(rule), projectName);
        }
    }
    private static String itemType(PatrolStandardEntity.CheckItem item) {
        return switch (item.type()) {
            case number -> "数值"; case text -> "文本"; case choice -> "选项"; case image -> "图像";
        };
    }
    /** Excel 单元格有长度上限；摘要可缩略，完整规则和检查项始终保留在明细表。 */
    private static String summary(List<String> parts) {
        String text = String.join("\n", parts);
        return text.length() > 30000 ? text.substring(0, 30000) + "…完整内容见明细表" : text;
    }
    private static String clock(int minute) { return String.format("%02d:%02d", minute / 60, minute % 60); }
}
