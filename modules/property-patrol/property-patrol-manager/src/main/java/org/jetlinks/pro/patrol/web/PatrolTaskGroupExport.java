package org.jetlinks.pro.patrol.web;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity;
import org.jetlinks.pro.patrol.entity.PatrolGenerationEntity;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.jetlinks.pro.patrol.service.PatrolInspection;
import org.jetlinks.pro.patrol.service.PatrolRecordExportService;
import org.jetlinks.pro.patrol.service.PatrolRecordExportService.Batch;
import org.jetlinks.pro.patrol.service.PatrolExportImages.Image;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 三表巡更记录工作簿；日任务编号关联执行记录，执行编号关联逐项检查结果。
 * 公共单元格按日任务纵向合并，但每次打卡仍独立一行。请求独占、阻塞调度器写入并关闭。
 */
final class PatrolTaskGroupExport implements AutoCloseable {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));
    private static final String[] COMMON = {"所属项目", "巡更点位", "点位分类", "点位等级", "归属日期"};
    private final SXSSFWorkbook workbook = new SXSSFWorkbook(100);
    private final Map<Sheet, Integer> columnCounts = new LinkedHashMap<>();
    private final Map<String, Picture> pictures = new LinkedHashMap<>();
    private final CellStyle textStyle;
    private final Sheet groups;
    private final Sheet executions;
    private final Sheet checks;
    private final long exportTime = System.currentTimeMillis();
    private long imageBytes;

    PatrolTaskGroupExport() {
        workbook.setCompressTempFiles(true);
        textStyle = workbook.createCellStyle(); textStyle.setWrapText(true);
        textStyle.setVerticalAlignment(org.apache.poi.ss.usermodel.VerticalAlignment.CENTER);
        groups = sheet("点位巡更记录", labels("巡更角色", "已巡次数", "应巡次数", "任务状态", "日任务编号"));
        executions = sheet("执行记录", labels("巡更时段", "巡更角色", "日任务编号", "执行编号", "执行人", "巡更时间", "采集网络状态", "备注"));
        checks = sheet("检查项结果", labels("巡更时段", "巡更角色", "日任务编号", "执行编号", "执行人", "巡更时间",
                "检查项名称", "检查类型", "检查结果", "结果异常", "数值下限", "数值上限", "选项内容", "正常选项", "参考说明", "现场图片", "例图"));
        executions.setColumnWidth(5, 48 * 256); checks.setColumnWidth(5, 48 * 256);
        checks.setColumnWidth(20, 42 * 256); checks.setColumnWidth(21, 42 * 256);
        groups.setColumnWidth(9, 44 * 256);
        for (var sheet : List.of(executions, checks)) {
            sheet.setColumnWidth(7, 44 * 256); sheet.setColumnWidth(8, 44 * 256);
        }
    }

    static byte[] write(List<PatrolGenerationEntity> groups) throws IOException {
        try (var writer = new PatrolTaskGroupExport()) {
            writer.append(new Batch(groups, List.of(), List.of())); return writer.finish();
        }
    }

    /** 每个附件只读取、编码、嵌入一次；仍遵循附件自身权限，不生成携带凭据的外链。 */
    Set<String> missingImages(Batch batch) {
        var ids = new LinkedHashSet<String>();
        for (var execution : batch.executions()) {
            for (var answer : orEmpty(execution.getAnswers())) addImageId(ids, answer.attachmentId());
            for (var item : orEmpty(execution.getStandardSnapshot())) addImageId(ids, item.exampleFileId());
        }
        ids.removeAll(pictures.keySet());
        if (pictures.size() + ids.size() > 4000) throw new BusinessException("error.patrol_record_export_image_limit");
        return ids;
    }
    private static void addImageId(Set<String> ids, String id) { if (id != null && !id.isBlank()) ids.add(id); }

    void addImage(String id, Image image) {
        imageBytes += image.bytes().length;
        if (imageBytes > 64L * 1024 * 1024) throw new BusinessException("error.patrol_record_export_image_limit");
        int index = image.bytes().length == 0 ? -1 : workbook.addPicture(image.bytes(), Workbook.PICTURE_TYPE_PNG);
        pictures.put(id, new Picture(index, image.message(), image.width(), image.height()));
    }

    void append(Batch batch) {
        var taskMap = index(batch.periods(), PatrolTaskEntity::getId);
        var executionsByGroup = batch.executions().stream().collect(Collectors.groupingBy(PatrolExecutionEntity::getTaskGroupId));
        for (var group : batch.groups()) {
            row(groups, context(group), role(group.getRoleId(), group.getRoleName()), group.getCompletedCount(),
                    group.getRequiredCount(), groupStatus(group), group.getId());
            var facts = new ArrayList<>(executionsByGroup.getOrDefault(group.getId(), List.of()));
            // 同一日任务连续排列，按真实时段、执行时间排序；不同角色的日任务不合并。
            facts.sort(Comparator.comparing((PatrolExecutionEntity fact) -> {
                var task = taskMap.get(fact.getTaskId()); return task == null ? Long.MAX_VALUE : task.getStartTime();
            }).thenComparing(PatrolExecutionEntity::getReceiveTime).thenComparing(PatrolExecutionEntity::getId));
            int first = executions.getLastRowNum() + 1;
            for (var execution : facts) {
                var task = taskMap.get(execution.getTaskId());
                var period = task == null ? "" : period(task.getStartTime(), task.getEndTime());
                row(executions, context(group), period, role(group.getRoleId(), group.getRoleName()), group.getId(),
                        execution.getId(), execution.getUserName(), time(execution.getReceiveTime()),
                        network(execution.getNetworkStatus()), execution.getRemark());
                writeChecks(group, execution, period);
            }
            int last = executions.getLastRowNum();
            if (last > first) {
                for (int column : new int[]{0, 1, 2, 3, 4, 6, 7}) {
                    executions.addMergedRegion(new CellRangeAddress(first, last, column, column));
                }
            }
        }
    }

    private void writeChecks(PatrolGenerationEntity group, PatrolExecutionEntity execution, String period) {
        var standards = index(orEmpty(execution.getStandardSnapshot()), PatrolStandardEntity.CheckItem::id);
        var answers = index(orEmpty(execution.getAnswers()), PatrolExecutionEntity.Answer::itemId);
        for (var id : PatrolRecordExportService.checkItemIds(execution)) {
            var standard = standards.get(id); var answer = answers.get(id);
            var type = standard == null ? null : standard.type();
            var result = row(checks, context(group), period, role(group.getRoleId(), group.getRoleName()), group.getId(),
                    execution.getId(), execution.getUserName(), time(execution.getReceiveTime()),
                    standard == null ? "" : standard.title(), type == null ? "" : type(type),
                    answer == null ? "" : answer.value(), PatrolInspection.resultOf(standard, answer) == PatrolExecutionEntity.CheckResult.fail ? "异常" : "",
                    type == PatrolStandardEntity.ItemType.number ? standard.lowerLimit() : null,
                    type == PatrolStandardEntity.ItemType.number ? standard.upperLimit() : null,
                    type == PatrolStandardEntity.ItemType.choice ? String.join("、", standard.options()) : "",
                    type == PatrolStandardEntity.ItemType.choice ? standard.options().stream().findFirst().orElse("") : "",
                    standard == null ? "" : standard.referenceText(), "", "");
            picture(checks, result, 20, answer == null ? null : answer.attachmentId());
            picture(checks, result, 21, standard == null ? null : standard.exampleFileId());
        }
    }

    private void picture(Sheet sheet, org.apache.poi.ss.usermodel.Row row, int column, String id) {
        if (id == null || id.isBlank()) return;
        var picture = pictures.get(id);
        if (picture == null || picture.index() < 0) {
            row.getCell(column).setCellValue(picture == null ? "图片未加载" : picture.message()); return;
        }
        row.setHeightInPoints(120);
        var anchor = workbook.getCreationHelper().createClientAnchor();
        anchor.setCol1(column); anchor.setCol2(column); anchor.setRow1(row.getRowNum()); anchor.setRow2(row.getRowNum());
        // 图片缩放后仍保持原比例，边缘留白；不把竖图或方图拉伸成单元格形状。
        double width = Math.max(1, picture.width()), height = Math.max(1, picture.height());
        double scale = Math.min(1, Math.min((sheet.getColumnWidthInPixels(column) - 8) / width, 152 / height));
        anchor.setDx1(org.apache.poi.util.Units.pixelToEMU(4)); anchor.setDy1(org.apache.poi.util.Units.pixelToEMU(4));
        anchor.setDx2(org.apache.poi.util.Units.pixelToEMU(4 + (int) (width * scale)));
        anchor.setDy2(org.apache.poi.util.Units.pixelToEMU(4 + (int) (height * scale)));
        anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
        sheet.createDrawingPatriarch().createPicture(anchor, picture.index());
    }

    byte[] finish() throws IOException {
        try (var out = new ByteArrayOutputStream()) {
            columnCounts.forEach((sheet, count) -> sheet.setAutoFilter(new CellRangeAddress(0, sheet.getLastRowNum(), 0, count - 1)));
            workbook.write(out); return out.toByteArray();
        }
    }
    @Override public void close() throws IOException { try { workbook.close(); } finally { workbook.dispose(); } }

    private Sheet sheet(String name, String[] labels) {
        var sheet = workbook.createSheet(name); var header = sheet.createRow(0);
        var style = workbook.createCellStyle(); var font = workbook.createFont(); font.setBold(true); style.setFont(font);
        for (int i = 0; i < labels.length; i++) {
            var cell = header.createCell(i); cell.setCellValue(labels[i]); cell.setCellStyle(style); sheet.setColumnWidth(i, 24 * 256);
        }
        sheet.createFreezePane(0, 1); columnCounts.put(sheet, labels.length); return sheet;
    }
    private org.apache.poi.ss.usermodel.Row row(Sheet sheet, Object[] common, Object... values) {
        var row = sheet.createRow(sheet.getLastRowNum() + 1);
        row.setHeightInPoints(sheet == checks ? 60 : 30);
        var cells = Arrays.copyOf(common, common.length + values.length); System.arraycopy(values, 0, cells, common.length, values.length);
        for (int i = 0; i < cells.length; i++) {
            var cell = row.createCell(i); cell.setCellStyle(textStyle);
            // 名称、备注和答案始终写为文本，不执行用户输入中的公式。
            if (cells[i] instanceof BigDecimal decimal) cell.setCellValue(decimal.toPlainString());
            else if (cells[i] instanceof Number number) cell.setCellValue(number.doubleValue());
            else cell.setCellValue(cells[i] == null ? "" : cells[i].toString());
        }
        return row;
    }
    private String groupStatus(PatrolGenerationEntity group) {
        if (group.getRequiredCount() == null || group.getRequiredCount() <= group.getCompletedCount()) return "已完成";
        if (group.getEndTime() != null && exportTime > group.getEndTime()) return group.getCompletedCount() == 0 ? "缺勤" : "漏检";
        return group.getStartTime() != null && exportTime < group.getStartTime() ? "未开始" : "进行中";
    }
    private static Object[] context(PatrolGenerationEntity group) {
        return new Object[]{group.getProjectName(), group.getPointName(), group.getCategoryName(), group.getLevelName(), group.getBelongDate()};
    }
    private static String period(Long from, Long to) { return time(from) + " ～ " + time(to); }
    private static String role(String id, String name) { return id == null || id.isBlank() ? "全员巡更" : name; }
    private static String time(Long value) { return value == null ? "" : TIME.format(Instant.ofEpochMilli(value)); }
    private static String network(PatrolExecutionEntity.NetworkStatus value) { return value == null ? "" : value == PatrolExecutionEntity.NetworkStatus.offline ? "离线" : "在线"; }
    private static String type(PatrolStandardEntity.ItemType value) { return switch (value) { case number -> "数值"; case text -> "文本"; case choice -> "选项"; case image -> "图像"; }; }
    private static String[] labels(String... extra) { var labels = Arrays.copyOf(COMMON, COMMON.length + extra.length); System.arraycopy(extra, 0, labels, COMMON.length, extra.length); return labels; }
    private static <T> List<T> orEmpty(List<T> values) { return values == null ? List.of() : values; }
    private static <T> Map<String, T> index(List<T> values, Function<T, String> key) { return values.stream().collect(Collectors.toMap(key, Function.identity(), (first, last) -> last, LinkedHashMap::new)); }
    private record Picture(int index, String message, int width, int height) {}
}
