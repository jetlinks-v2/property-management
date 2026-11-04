package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.reactor.excel.CellDataType;
import org.hswebframework.reactor.excel.ExcelHeader;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import java.util.*;
import java.util.function.Function;
import java.util.stream.IntStream;

/** 文件和页面使用同一业务字段；工单、审核、评价与项目汇总保持独立投影。 */
final class RepairExportTables {
    private RepairExportTables() {}
    record Column<T>(String title, Function<T, Object> value, CellDataType type) {
        Column(String title,Function<T,Object> value) { this(title,value,CellDataType.STRING); }
    }
    private static <T> Column<T> number(String title,Function<T,? extends Number> value) {
        return new Column<>(title,value::apply,CellDataType.NUMBER);
    }
    record Row(List<String> orderIds, Map<String,Object> values) {}

    private static final String[] statuses = {"待分派","处理中","已完结","已废弃","已关闭","已归档","待接单","待跟进","待回访","待审核"};
    private static String urgency(Integer value) { return value == null ? "" : switch (value) { case 1 -> "一般"; case 2 -> "紧急"; case 3 -> "非常紧急"; default -> ""; }; }
    private static String reviewKind(String value) { return value == null ? "" : switch (value) { case "delay" -> "延期审核"; case "close" -> "非关审核"; case "levelChange" -> "等级变更审核"; case "archive" -> "归档前等级审核"; case "abandon" -> "废弃审核"; default -> value; }; }
    private static String reviewStatus(Integer value) { return value == null ? "" : switch (value) { case 1 -> "待审核"; case 2 -> "已通过"; case 3 -> "不通过"; default -> ""; }; }

    static final List<Column<RepairOrderExportRow>> ORDERS = List.of(
        new Column<>("报事编号", RepairOrderEntity::getNumber), new Column<>("项目名称", RepairOrderEntity::getBusinessProjectName),
        new Column<>("报事渠道", RepairOrderEntity::getChannelName),
        new Column<>("报事分类（一级）", RepairOrderExportRow::getFirstCategoryName),
        new Column<>("报事分类（二级）", RepairOrderExportRow::getSecondCategoryName),
        new Column<>("报事分类", RepairOrderEntity::getCategoryName),
        new Column<>("报事内容", RepairOrderEntity::getDescription), new Column<>("报事来源", RepairOrderEntity::getSource),
        new Column<>("报事位置", o -> o.getPositionType() == null ? "" : o.getPositionType() == 2 ? "室内" : "公区"),
        new Column<>("紧急程度", o -> urgency(o.getUrgency())), new Column<>("房间号", RepairOrderEntity::getSpaceName),
        new Column<>("具体位置", RepairOrderEntity::getLocation), new Column<>("报事标签", o -> o.getLabelNames() == null ? "" : String.join("、",o.getLabelNames())),
        new Column<>("报事人", RepairOrderEntity::getContactName), new Column<>("联系方式", RepairOrderEntity::getContactPhone),
        new Column<>("报事登记人", RepairOrderEntity::getCreatorName),
        new Column<>("登记人手机号", RepairOrderEntity::getRegisterPhone), new Column<>("责任人", RepairOrderEntity::getAssignedUserName),
        new Column<>("完结时限", o -> RepairCsv.time(o.getFinishDeadline())),
        new Column<>("状态", o -> o.getStatus() == null ? "" : statuses[o.getStatus()]),
        new Column<>("报事时间", o -> RepairCsv.time(o.getCreateTime())), new Column<>("接单时间", o -> RepairCsv.time(o.getAcceptTime())),
        new Column<>("完结时间", o -> RepairCsv.time(o.getFinishTime())), new Column<>("归档时间", o -> RepairCsv.time(o.getArchiveTime())),
        number("材料费",RepairOrderEntity::getMaterialAmount), number("人工费",RepairOrderEntity::getArtificialAmount),
        number("合计费用",RepairOrderEntity::getTotalAmount), number("责任人抽佣",RepairOrderEntity::getPersonAmount),
        number("供方抽佣",RepairOrderEntity::getProviderAmount), number("物业抽佣",RepairOrderEntity::getPropertyAmount),
        number("员工满意度",RepairOrderEntity::getStaffSatisfaction), number("业主满意度",RepairOrderEntity::getOwnerSatisfaction),
        new Column<>("是否评价", o -> Boolean.TRUE.equals(o.getStaffEvaluated()) || Boolean.TRUE.equals(o.getOwnerEvaluated()) ? "是" : "否")
    );
    static final List<Column<RepairReviewOrderRow>> REVIEWS = List.of(
        new Column<>("报事编号", RepairReviewOrderRow::getNumber), new Column<>("项目名称", RepairReviewOrderRow::getBusinessProjectName),
        new Column<>("报事登记人", RepairReviewOrderRow::getCreatorName), new Column<>("报事人", RepairReviewOrderRow::getContactName),
        new Column<>("联系方式", RepairReviewOrderRow::getContactPhone), new Column<>("报事时间", o -> RepairCsv.time(o.getCreateTime())),
        new Column<>("报事内容", RepairReviewOrderRow::getDescription), new Column<>("紧急程度", o -> urgency(o.getUrgency())),
        new Column<>("报事渠道", RepairReviewOrderRow::getChannelName), new Column<>("报事分类", RepairReviewOrderRow::getCategoryName),
        new Column<>("完结时限", o -> RepairCsv.time(o.getFinishDeadline())), new Column<>("申请时间", o -> RepairCsv.time(o.getReviewCreateTime())),
        new Column<>("申请人", RepairReviewOrderRow::getReviewApplicantName), new Column<>("申请原因", RepairReviewOrderRow::getReviewReason),
        new Column<>("审核类型", o -> reviewKind(o.getReviewKind())), new Column<>("审核状态", o -> reviewStatus(o.getReviewStatus())),
        new Column<>("审核人", RepairReviewOrderRow::getReviewReviewerName), new Column<>("审核意见", RepairReviewOrderRow::getReviewOpinion),
        new Column<>("审核时间", o -> RepairCsv.time(o.getReviewTime()))
    );
    static final List<Column<RepairOrderEntity>> EVALUATIONS = List.of(
        new Column<>("报事编号", RepairOrderEntity::getNumber),new Column<>("项目名称", RepairOrderEntity::getBusinessProjectName),
        new Column<>("报事内容", RepairOrderEntity::getDescription),new Column<>("责任人", RepairOrderEntity::getAssignedUserName),
        new Column<>("员工是否评价", RepairOrderEntity::getStaffEvaluated),number("员工满意度",RepairOrderEntity::getStaffSatisfaction),
        new Column<>("员工满意度名称", RepairOrderEntity::getStaffSatisfactionName),
        new Column<>("业主是否评价", RepairOrderEntity::getOwnerEvaluated),number("业主满意度",RepairOrderEntity::getOwnerSatisfaction),
        new Column<>("业主满意度名称", RepairOrderEntity::getOwnerSatisfactionName),
        new Column<>("业主姓名", RepairOrderEntity::getEvaluationOwnerName),new Column<>("业主联系方式", RepairOrderEntity::getEvaluationOwnerPhone),
        new Column<>("评价登记人", RepairOrderEntity::getOwnerEvaluationRecorderName),new Column<>("评价时间", o -> RepairCsv.time(o.getOwnerEvaluationTime()))
    );
    private static final List<Column<RepairReportRow>> STATES = List.of(
        number("待分派",RepairReportRow::getNoDistributed),number("待接单",RepairReportRow::getWaitAccept),
        number("待跟进",RepairReportRow::getWaitFollow),number("处理中",RepairReportRow::getHandling),
        number("已完成",RepairReportRow::getFinished),number("待回访",RepairReportRow::getWaitCallback),
        number("待审核",RepairReportRow::getWaitAudit),number("已归档",RepairReportRow::getArchived),
        number("已关闭",RepairReportRow::getClose),number("已废弃",RepairReportRow::getAbandoned));

    static List<Column<RepairReportRow>> report(RepairExportType type) {
        var columns = new ArrayList<Column<RepairReportRow>>();
        columns.add(new Column<>("项目名称",RepairReportRow::getProjectName));
        switch(type) {
            case node -> columns.addAll(STATES);
            case paid -> columns.addAll(List.of(number("有偿报事接单量",RepairReportRow::getPaidCount),
                new Column<>("好评率",r -> r.getPaidPositiveRate()+"%"),number("抽佣提成金额（元）",RepairReportRow::getCommissionAmount),
                number("材料费",RepairReportRow::getMaterialAmount),number("人工费",RepairReportRow::getArtificialAmount),
                number("合计费用",RepairReportRow::getTotalAmount),number("物业抽佣",RepairReportRow::getPropertyAmount)));
            case overdue -> columns.addAll(List.of(number("30～59(天)",RepairReportRow::getRange1),number("60～89(天)",RepairReportRow::getRange2),
                number("90～119(天)",RepairReportRow::getRange3),number("120～365(天)",RepairReportRow::getRange4),number("1年以上",RepairReportRow::getRange5)));
            case follow -> columns.addAll(List.of(number("30分钟",RepairReportRow::getFollow1),number("1小时",RepairReportRow::getFollow2),
                number("2小时",RepairReportRow::getFollow3),number("1天以上",RepairReportRow::getFollow4)));
            case summary -> {
                columns.add(number("报事发生量",RepairReportRow::getTotal));columns.addAll(STATES);
                columns.addAll(List.of(new Column<>("待分派率",r -> r.getNoDistributedRate()+"%"),new Column<>("接单率",r -> r.getOrderRate()+"%"),
                    new Column<>("废弃率",r -> r.getAbandonedRate()+"%"),new Column<>("报事完成率",r -> r.getReportCompletionRate()+"%"),
                    new Column<>("及时抢单率",r -> r.getOrderGrabbingRate()+"%"),new Column<>("完成及时率",r -> r.getTimelyCompletionRate()+"%"),
                    new Column<>("业主评价率",r -> r.getOwnerEvaluationRate()+"%"),new Column<>("报事好评率",r -> r.getPositiveReviewRate()+"%")));
            }
            default -> throw new IllegalArgumentException("Not a report projection");
        }
        return List.copyOf(columns);
    }
    static List<ExcelHeader> headers(RepairExportType type) {
        List<? extends Column<?>> columns = switch(type) {case orders -> ORDERS;case review -> REVIEWS;case evaluation -> EVALUATIONS;default -> report(type);};
        return IntStream.range(0,columns.size()).mapToObj(i -> new ExcelHeader("c"+i,columns.get(i).title(),columns.get(i).type())).toList();
    }
    static <T> Row row(T value,List<Column<T>> columns,List<String> sourceIds) {
        Map<String,Object> cells=new LinkedHashMap<>();
        for(int i=0;i<columns.size();i++) {
            Object cell=columns.get(i).value().apply(value);
            // 文本保持字面值，CSV与Excel均不把用户输入解释为公式。
            if(cell instanceof String text && !text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0))>=0) cell="'"+text;
            cells.put("c"+i,cell == null ? "" : cell);
        }
        return new Row(sourceIds,cells);
    }
}
