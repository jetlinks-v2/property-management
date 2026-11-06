package org.jetlinks.pro.property.decoration.model;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 装修聚合的冻结快照；配置变更不修改已提交申请、验收和巡检的历史。 */
public final class DecorationModels {
    private DecorationModels() {}
    @Getter @Setter public static class MaterialDefinition {
        private String id;
        private String name;
        private String kind; // material / drawing
        private String source; // upload / sign
        private String templateFileId;
        private boolean required;
        private boolean enabled = true;
        private int sort;
    }
    @Getter @Setter public static class Material {
        private MaterialDefinition definition;
        private List<String> fileIds = new ArrayList<>();
        private int reviewStatus;
        private String reviewerId;
        private String reviewerName;
        private Long reviewTime;
        private String remark;
        private String annotatedFileId;
    }
    @Getter @Setter public static class CheckItem {
        private String id;
        private String category;
        private Integer categorySort;
        private String name;
        private String inputType;
        private boolean required;
        private boolean enabled = true;
        private String standard;
        private BigDecimal min;
        private BigDecimal max;
        private List<String> options = new ArrayList<>();
        private String example;
        private String exampleFileId;
        private int sort;
    }
    @Getter @Setter public static class CheckResult {
        private String itemId;
        private String value;
        private List<String> fileIds = new ArrayList<>();
        private Boolean qualified;
        private String rectification;
        private Long deadline;
    }
    @Getter @Setter public static class Agreement {
        private String title;
        private String content;
        private String chargeItemId;
        private String chargeItemName;
        private BigDecimal deposit;
        private BigDecimal firstRefund;
        private BigDecimal secondRefund;
        private java.util.Map<String, BigDecimal> fees = new java.util.LinkedHashMap<>();
        private List<Supplement> supplements = new ArrayList<>();
    }
    @Getter @Setter public static class Supplement {
        private String name;
        private String content;
    }
    @Getter @Setter public static class Configuration {
        private Agreement agreement;
        private List<MaterialDefinition> materials = new ArrayList<>();
        private List<CheckItem> checks = new ArrayList<>();
        private List<String> eligibleNatures = new ArrayList<>();
        private Integer validityMonths;
    }
    @Getter @Setter public static class ApplicationDetails {
        private String ownerCertificate;
        private String constructionUnit;
        private String constructionManager;
        private String managerPhone;
        private String managerCertificate;
        private int constructionType;
        private String decorationType;
        private String houseType;
        private Long startTime;
        private Long completionTime;
        private String selfInspectionRemark;
        private int selfInspectionStatus;
        private List<String> selfInspectionFiles = new ArrayList<>();
        private boolean noticesAccepted;
        private List<String> signatureFiles = new ArrayList<>();
        private List<Material> materials = new ArrayList<>();
        private Agreement agreement;
    }
    @Getter @Setter public static class Permit {
        private String number;
        private String safetyPerson;
        private String safetyPhone;
        private String content;
        private boolean entrusted;
        private List<Worker> workers = new ArrayList<>();
        private Long startTime;
        private Long endTime;
        private int validityMonths;
        private Long printTime;
        private String approverId;
        private String approverName;
        private String approverPhone;
        private List<Extension> extensions = new ArrayList<>();
    }
    @Getter @Setter public static class Worker {
        private String name;
        private String certificate;
        private String phone;
        private Long entryTime;
        private Long expirationTime;
    }
    @Getter @Setter public static class Extension {
        private Long beforeEndTime;
        private Long afterEndTime;
        private int months;
        private String actorId;
        private String actorName;
        private Long time;
    }
    /** 角色周期按上海自然日计算；角色名称由公开目录验证后冻结。 */
    public record InspectionRole(@jakarta.validation.constraints.NotBlank String roleId, String roleName,
        @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(1000) int intervalDays) {}
    @Getter @Setter public static class TaskDetails {
        private List<CheckItem> checks = new ArrayList<>();
        private List<CheckResult> results = new ArrayList<>();
        private List<String> fileIds = new ArrayList<>();
        private String feedback;
        private Long deadline;
        private List<InspectionRole> inspectionRoles = new ArrayList<>();
        private String scheduledDate;
        private String assignedById;
        private String assignedByName;
        private Long startTime;
        private Long endTime;
        private String auditRoleId;
        private String auditRoleName;
        private String reviewRoleId;
        private String reviewRoleName;
        private List<Violation> violations = new ArrayList<>();
        private List<String> rectificationFiles = new ArrayList<>();
        private String rectificationRemark;
        private List<TaskHandlingRecord> handlingRecords = new ArrayList<>();
    }
    /** 办理快照与任务同事务保存；后续复核、重交和角色变更不得覆盖此前凭据。 */
    public record TaskHandlingRecord(String action, String status, String operatorId, String operatorName,
        long time, String remark, List<String> fileIds, String reviewRoleId, String reviewRoleName) {}
    @Getter @Setter public static class Violation {
        private String location;
        private String rectification;
        private List<String> fileIds = new ArrayList<>();
    }
}
