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
            headers(sheet, level ? new String[]{"点位等级", "状态", "编辑人", "最后编辑时间", "备注"}
                    : standard ? new String[]{"标准名称", "检查项数量", "状态", "最后编辑时间", "备注"}
                    : new String[]{"标签名称", "状态", "最后编辑时间", "备注"});
            Sheet rules = level ? book.createSheet("巡更规则") : null;
            if (rules != null) headers(rules, new String[]{"点位等级", "星期或高级日期", "时段序号", "开始", "结束", "跨天或全天", "巡更频率（分钟）", "每次频率应巡次数", "最小间隔（分钟）"});
            Sheet items = standard ? book.createSheet("检查项目") : null;
            if (items != null) headers(items, new String[]{"标准名称", "序号", "检查项名称", "类型", "必填", "必拍", "选项", "合格选项", "数值下限", "数值上限", "参考说明"});
            for (var entity : rows) {
                String time = entity.getModifyTime() == null ? "" : TIME.format(Instant.ofEpochMilli(entity.getModifyTime()));
                String status = entity.isEnabled() ? "启用" : "停用";
                if (level) {
                    values(sheet, entity.getName(), status, entity.getModifierName(), time, entity.getDescription());
                    var rule = ((PatrolLevelEntity) entity).getRule();
                    if (rule != null) {
                        if (rule.weekly() != null) new TreeMap<>(rule.weekly()).forEach((day, windows) ->
                                ruleValues(rules, entity.getName(), "星期" + "一二三四五六日".charAt(day - 1), windows, rule));
                        if (rule.dates() != null) new TreeMap<>(rule.dates()).forEach((date, windows) ->
                                ruleValues(rules, entity.getName(), date.toString(), windows, rule));
                    }
                }
                else if (standard) {
                    var checkItems = ((PatrolStandardEntity) entity).getItems();
                    values(sheet, entity.getName(), checkItems.size(), status, time, entity.getDescription());
                    for (int i = 0; i < checkItems.size(); i++) {
                        var item = checkItems.get(i);
                        values(items, entity.getName(), i + 1, item.title(), switch (item.type()) {
                            case number -> "数值"; case text -> "文本"; case choice -> "选项"; case image -> "图像";
                        }, item.required() ? "是" : "否", item.photoRequired() ? "是" : "否",
                                String.join("、", item.options()), String.join("、", item.qualifiedOptions()),
                                item.lowerLimit(), item.upperLimit(), item.referenceText());
                    }
                } else values(sheet, entity.getName(), status, time, entity.getDescription());
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
        for (int i = 0; i < values.length; i++) {
            var cell = row.createCell(i);
            // 精确数值上下限按十进制文本导出，避免 Excel 浮点精度损失；只有计数写数字。
            if (values[i] instanceof Integer count) cell.setCellValue(count);
            else cell.setCellValue(values[i] == null ? "" : values[i].toString());
        }
    }
    private static long ruleRows(PatrolRule rule) {
        if (rule == null) return 0;
        return (rule.weekly() == null ? 0 : rule.weekly().values().stream().mapToLong(w -> Math.max(1, w.size())).sum())
                + (rule.dates() == null ? 0 : rule.dates().values().stream().mapToLong(w -> Math.max(1, w.size())).sum());
    }
    private static void ruleValues(Sheet sheet, String name, String label, List<PatrolRule.Window> windows, PatrolRule rule) {
        if (windows.isEmpty()) { values(sheet, name, label, "不巡更"); return; }
        for (int i = 0; i < windows.size(); i++) {
            var window = windows.get(i);
            values(sheet, name, label, i + 1, clock(window.startMinute()), clock(window.endMinute()),
                    window.endMinute() < window.startMinute() ? "次日" : window.endMinute() == window.startMinute() ? "全天" : "",
                    window.intervalMinutes(), window.requiredCount(), window.cooldown(rule));
        }
    }
    private static String clock(int minute) { return String.format("%02d:%02d", minute / 60, minute % 60); }
}
