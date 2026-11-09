package org.jetlinks.pro.smartpark.handover.excel;

import org.hswebframework.web.exception.BusinessException;

import java.util.List;
import java.util.Set;

/** 中文表头是导入、模板、导出的共同契约；扩展设备字段保留旧字段名，不凭列位置猜测。 */
public final class HandoverExcelSchema {
    public record Column(String key, String title, boolean required, String example) {}

    private HandoverExcelSchema() {}

    private static Column c(String key, String title, boolean required, String example) {
        return new Column(key, title, required, example);
    }

    public static final List<Column> EQUIPMENT =
            List.of(
                    new Column("name", "设备名称", true, "给水泵"),
                    new Column("code", "设备编号", false, "SB-001"),
                    new Column("categoryName", "设备分类", false, "消防设备"),
                    new Column("unit", "单位", false, "台"),
                    new Column("quantity", "数量", false, "1"),
                    new Column("controlArea", "控制区域", false, ""),
                    new Column("mainParameters", "主要参数", false, ""),
                    new Column("specification", "规格型号", false, ""),
                    new Column("properties.factoryCode", "出厂编号", false, ""),
                    new Column("properties.assetNumber", "资产编号", false, ""),
                    new Column("manufacturer", "生产厂家", false, ""),
                    new Column("properties.manufacturerContact", "厂家联系方式", false, ""),
                    new Column("properties.manufacturerAddress", "厂家地址", false, ""),
                    new Column("supplier", "供货厂家/供应商", false, ""),
                    new Column("properties.supplierContact", "供应商联系方式", false, ""),
                    new Column("properties.ratedVoltage", "额定电压(V)", false, ""),
                    new Column("properties.ratedCurrent", "额定电流(A)", false, ""),
                    new Column("properties.ratedPower", "额定功率(kW)", false, ""),
                    new Column("properties.ratedFrequency", "额定频率(Hz)", false, ""),
                    new Column("properties.ratedSpeed", "额定转速(r/min)", false, ""),
                    new Column("properties.ratedPressure", "额定压力(MPa)", false, ""),
                    new Column("properties.ratedTemperature", "额定温度(℃)", false, ""),
                    new Column("properties.ratedFlow", "额定流量(m³/h)", false, ""),
                    new Column("properties.ratedLoad", "额定载重量(t/kg)", false, ""),
                    new Column("properties.ratedCapacity", "额定容量(L/m³)", false, ""),
                    new Column("properties.equipmentWeight", "设备重量(kg)", false, ""),
                    new Column("properties.equipmentDimensions", "设备外形尺寸", false, ""),
                    new Column("properties.motorPower", "电机功率(kW)", false, ""),
                    new Column("properties.motorSpeed", "电机转速(r/min)", false, ""),
                    new Column("properties.motorVoltage", "电机电压(V)", false, ""),
                    new Column("properties.motorCurrent", "电机电流(A)", false, ""),
                    new Column("properties.motorProtectionLevel", "电机防护等级", false, ""),
                    new Column("properties.motorInsulationClass", "电机绝缘等级", false, ""),
                    new Column("properties.volume", "容积(L/m³)", false, ""),
                    new Column("properties.designPressure", "设计压力(MPa)", false, ""),
                    new Column("properties.designTemperature", "设计温度(℃)", false, ""),
                    new Column("properties.workingPressure", "工作压力(MPa)", false, ""),
                    new Column("properties.workingMedium", "工作介质", false, ""),
                    new Column("properties.elevatorRatedLoad", "电梯额定载重量(kg)", false, ""),
                    new Column("properties.elevatorRatedSpeed", "电梯额定速度(m/s)", false, ""),
                    new Column("properties.elevatorStops", "电梯停靠站数", false, ""),
                    new Column("properties.elevatorShaftHeight", "电梯井道高度(mm)", false, ""),
                    new Column("location", "所在位置(楼层)", false, ""),
                    new Column("properties.installationLocation", "安装位置", false, ""),
                    new Column("installationDate", "安装日期", false, ""),
                    new Column("commissionDate", "启用日期", false, ""),
                    new Column("properties.installationUnit", "安装单位", false, ""),
                    new Column("properties.installationContact", "安装单位联系方式", false, ""),
                    new Column("properties.ownershipUnit", "产权单位", false, ""),
                    new Column("properties.managementUnit", "管理单位", false, ""),
                    new Column("properties.originalValue", "设备原值(元)", false, ""),
                    new Column("properties.purchaseDate", "购置日期", false, ""),
                    new Column("properties.purchasePrice", "购置价格(元)", false, ""),
                    new Column("properties.supplierContract", "供应商合同号", false, ""),
                    new Column("properties.invoiceNumber", "发票号码", false, ""),
                    new Column("productionDate", "生产日期", false, ""),
                    new Column("warrantyEndDate", "质保期至", false, ""),
                    new Column("properties.serviceLife", "设计使用年限(年)", false, ""),
                    new Column("properties.nextInspectionDate", "下次检验日期", false, ""),
                    new Column("properties.lastInspectionDate", "上次检验日期", false, ""),
                    new Column("properties.inspectionCycle", "检验周期(月)", false, ""),
                    new Column("properties.equipmentStatusName", "设备状态", false, ""),
                    new Column("properties.runningStatusName", "运行状态", false, ""),
                    new Column("properties.healthStatusName", "健康状态", false, ""),
                    new Column("properties.lastMaintenanceDate", "上次维护日期", false, ""),
                    new Column("properties.nextMaintenanceDate", "下次维护日期", false, ""),
                    new Column("properties.maintenanceCycle", "维护周期(天)", false, ""),
                    new Column("properties.maintenanceUnit", "维保单位", false, ""),
                    new Column("properties.maintenanceContact", "维保联系方式", false, ""),
                    new Column("properties.maintenanceContract", "维保合同号", false, ""),
                    new Column("properties.certificateNumber", "设备证书号", false, ""),
                    new Column("properties.certificateExpiryDate", "证书有效期至", false, ""),
                    new Column("properties.licenseNumber", "许可证号", false, ""),
                    new Column("properties.licenseExpiryDate", "许可证有效期至", false, ""),
                    new Column("brand", "设备品牌", false, ""),
                    new Column("properties.color", "颜色", false, ""),
                    new Column("properties.material", "材质", false, ""),
                    new Column("properties.ipRating", "防护等级", false, ""),
                    new Column("properties.explosionProofName", "是否防爆", false, ""),
                    new Column("properties.energyEfficiency", "能效等级", false, ""),
                    new Column("properties.environmentalRating", "环境等级", false, ""),
                    new Column("properties.equipmentDescription", "设备描述", false, ""),
                    new Column("remark", "备注", false, ""),
                    new Column("properties.problemDescription", "问题描述/缺陷记录", false, ""));

    public static List<Column> columns(String kind) {
        return switch (kind) {
            case "category" ->
                    List.of(
                            c("name", "分类名称", true, "给排水"),
                            c("parentName", "上级分类", false, ""),
                            c("sortIndex", "排序", false, "0"),
                            c("description", "说明", false, ""));
            case "ledger" ->
                    List.of(
                            c("projectName", "项目名称", true, "请填写有权限的项目全称"),
                            c("categoryName", "问题分类", true, "请填写项目已配置的问题分类"),
                            c("description", "问题描述", true, "请填写现场实际情况"),
                            c("organizationName", "责任单位", true, "请填写项目已配置的责任单位"),
                            c("location", "问题位置", true, "1栋大堂"),
                            c("photos", "问题图片", false, ""),
                            c("inspectionTime", "检查时间", false, "2026-11-09 09:00:00"),
                            c("entryUserName", "发起人", false, ""));
            case "equipment" -> EQUIPMENT;
            case "record" ->
                    List.of(
                            c("title", "主题", true, "本次资料移交"),
                            c("categoryName", "资料分类", false, ""),
                            c("description", "内容", false, ""),
                            c("personNames", "参与人员", false, ""),
                            c("participantNames", "其他参与人", false, ""),
                            c("time", "业务时间", false, "2026-11-04 09:00:00"),
                            c("properties.meetingLocation", "会议地点", false, "会议室"),
                            c(
                                    "properties.meetingStartTime",
                                    "会议开始时间",
                                    false,
                                    "2026-11-04 09:00:00"),
                            c("properties.meetingEndTime", "会议结束时间", false, "2026-11-04 10:00:00"),
                            c("properties.submitLocation", "移交地点", false, "现场"),
                            c("properties.submitUserName", "移交人", false, ""),
                            c("properties.submitUserPhone", "移交人电话", false, ""),
                            c("properties.receiveUserName", "接收人", false, ""),
                            c("properties.receiveUserPhone", "接收人电话", false, ""),
                            c("properties.materialComplete", "资料齐全", false, "不齐全"),
                            c("properties.disclosureCategory", "交底分类", false, "消防系统"),
                            c("properties.disclosureUserName", "交底人", false, ""),
                            c("receiverName", "交底接收人", false, ""),
                            c("properties.result", "结论", false, ""),
                            c("remark", "备注", false, ""),
                            c("photos", "附件图片", false, ""));
            case "item" ->
                    List.of(
                            c("name", "清单名称", true, "给水泵"),
                            c("code", "清单编号", false, "SB-001"),
                            c("specification", "规格型号", false, ""),
                            c("standard", "验收标准", false, ""),
                            c("quantity", "数量", false, "1"),
                            c("unit", "单位", false, "台"),
                            c("remark", "备注", false, ""));
            case "problem" ->
                    List.of(
                            c("description", "问题描述", true, "请填写现场实际情况"),
                            c("location", "位置", false, "1栋大堂"),
                            c("categoryName", "问题分类", false, ""),
                            c("organizationName", "责任单位", false, ""),
                            c("itemName", "清单名称", false, ""),
                            c("itemCode", "清单编号", false, ""),
                            c("inspectionType", "查验类别", false, "承接查验"),
                            c("inspectionTime", "查验时间", false, "2026-11-04 09:00:00"),
                            c("initiatorName", "发起人", false, ""),
                            c("photos", "问题图片", false, ""));
            default -> throw new BusinessException("error.handover.excel_kind_invalid", 400);
        };
    }

    /** 模板按阶段裁剪并标明必填；通用解析/导出保留完整字段集合。 */
    public static List<Column> columns(String kind, String stage) {
        if (!"record".equals(kind) || stage == null) return columns(kind);
        Set<String> stageKeys =
                switch (stage) {
                    case "kickoff" ->
                            Set.of(
                                    "properties.meetingLocation",
                                    "properties.meetingStartTime",
                                    "properties.meetingEndTime");
                    case "material" ->
                            Set.of(
                                    "properties.submitLocation",
                                    "properties.submitUserName",
                                    "properties.submitUserPhone",
                                    "properties.receiveUserName",
                                    "properties.receiveUserPhone",
                                    "properties.materialComplete");
                    case "disclosure" ->
                            Set.of(
                                    "properties.disclosureCategory",
                                    "properties.disclosureUserName",
                                    "receiverName");
                    default -> Set.of();
                };
        return columns(kind).stream()
                .filter(
                        c ->
                                (!c.key().startsWith("properties.")
                                                && !c.key().equals("receiverName"))
                                        || stageKeys.contains(c.key()))
                .filter(c -> !("disclosure".equals(stage) && "categoryName".equals(c.key())))
                .map(
                        c ->
                                new Column(
                                        c.key(),
                                        c.title(),
                                        c.required()
                                                || stageKeys.contains(c.key())
                                                || ("time".equals(c.key())
                                                        && Set.of("material", "disclosure")
                                                                .contains(stage)),
                                        c.example()))
                .toList();
    }

    public static void validateSelector(String kind, String source, String stage) {
        columns(kind);
        if ("record".equals(kind)
                && !Set.of(
                                "kickoff",
                                "material",
                                "disclosure",
                                "site",
                                "physical",
                                "rectification",
                                "quality",
                                "summary")
                        .contains(stage == null ? "" : stage))
            throw new BusinessException("error.handover.stage_invalid", 400);
        if ("item".equals(kind)
                && !Set.of("MATERIAL_TRANSFER", "PHYSICAL_HANDOVER", "QUALITY_ACCEPTANCE")
                        .contains(source == null ? "" : source))
            throw new BusinessException("error.handover.source_invalid", 400);
        // 旧质保问题导入只有控制器壳而没有可用写服务；保留真正可用的验收清单入口。
        if ("problem".equals(kind)
                && !Set.of("SITE_INSPECTION", "PHYSICAL_HANDOVER")
                        .contains(source == null ? "" : source))
            throw new BusinessException("error.handover.excel_source_unsupported", 400);
    }
}
