package org.jetlinks.pro.property.decoration.model;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 外部请求不接受持久化状态、业务快照或认证身份；只包含对应动作的可编辑字段。 */
public final class DecorationRequests {
    private DecorationRequests() {}
    /** 沿用原系统联系电话和15/18位身份证格式；这里只校验格式，不据此确认本人身份。 */
    public static final String PHONE_FORMAT = "(?:1[3-9]\\d{9}|\\d{8}|0\\d{2,3}-?\\d{7,8})";
    public static final String IDENTITY_FORMAT = "(?:\\d{15}|\\d{17}[\\dXx])";
    @Getter @Setter public static class Create {
        @NotBlank @Size(max=64) private String requestId;
        @NotBlank @Size(max=64) private String projectId;
        @NotBlank @Size(max=64) private String spaceId;
        @NotBlank @Size(max=64) private String customerId;
        @NotBlank @Pattern(regexp=PHONE_FORMAT,message="{error.decoration.invalid_owner_phone}") @Size(max=64) private String contactPhone;
        @NotBlank @Size(max=128) private String constructionUnit;
        @NotBlank @Size(max=128) private String constructionManager;
        @NotBlank @Pattern(regexp=PHONE_FORMAT,message="{error.decoration.invalid_manager_phone}") @Size(max=64) private String managerPhone;
        @NotBlank @Pattern(regexp=IDENTITY_FORMAT,message="{error.decoration.invalid_owner_identity}") @Size(max=18) private String ownerCertificate;
        @NotBlank @Pattern(regexp=IDENTITY_FORMAT,message="{error.decoration.invalid_manager_identity}") @Size(max=18) private String managerCertificate;
        @Min(1) @Max(2) private int constructionType;
        @NotBlank @Size(max=64) private String decorationType;
        @NotNull private Long startTime;
        @NotNull private Long completionTime;
        @AssertTrue private boolean noticesAccepted;
        @Size(max=2000) private String selfInspectionRemark;
        @Min(0) @Max(1) private int selfInspectionStatus;
        @Size(max=20) private List<String> selfInspectionFiles = new ArrayList<>();
        @Size(max=20) private List<String> signatureFiles = new ArrayList<>();
        @Valid @Size(min=4,max=4) private List<SignedDocument> signedDocuments = new ArrayList<>();
        @Valid @Size(max=200) private List<MaterialUpload> materials = new ArrayList<>();
    }
    @Getter @Setter public static class SignedDocument {
        @NotBlank private String kind;
        @NotBlank @Size(max=40000) private String content;
        @NotBlank @Size(max=64) private String signatureFileId;
        @NotBlank @Size(max=64) private String documentFileId;
    }
    @Getter @Setter public static class MaterialUpload {
        @NotBlank @Size(max=64) private String itemId;
        @NotEmpty @Size(max=20) private List<String> fileIds = new ArrayList<>();
    }
    @Getter @Setter public static class Action {
        @NotBlank @Size(max=64) private String requestId;
        @NotNull @Min(1) private Long version;
        @Size(max=2000) private String remark;
    }
    /** 完整证件先保存为私有图片，打印登记绑定图片回执及预览时的章摘要。 */
    @Getter @Setter public static class PrintPermit extends Action {
        @NotBlank @Pattern(regexp="[a-f0-9]{64}") private String sealDigest;
        @NotBlank @Size(max=64) private String snapshotFileId;
    }
    /** 领取人可为业主或代领人；操作员身份只从认证上下文取得。 */
    @Getter @Setter public static class CollectPermit extends Action {
        @NotBlank @Size(max=128) private String collectedBy;
        @NotNull @Min(1) private Long collectedTime;
    }
    @Getter @Setter public static class Review extends Action {
        @NotBlank private String itemId;
        @Min(1) @Max(2) private int result;
        private String annotatedFileId;
        private String annotationSourceFileId;
        @Valid @Size(max=50) private List<DecorationModels.ImageMark> annotations = new ArrayList<>();
        @DecimalMin("0.01") @DecimalMax("9999999999.99") @Digits(integer=10,fraction=2) private BigDecimal amount;
    }
    @Getter @Setter public static class Resubmit extends Action {
        @Valid @NotEmpty @Size(max=200) private List<MaterialUpload> materials = new ArrayList<>();
    }
    @Getter @Setter public static class ChangeEndTime extends Action { @NotNull private Long completionTime; }
    /** 管理端登记或纠正实际结束时间，不接受清空以重新激活装修。 */
    @Getter @Setter public static class ChangeActualEndTime extends Action { @NotNull @Min(1) private Long actualEndTime; }
    @Getter @Setter public static class IssuePermit extends Action {
        @NotBlank private String safetyPerson;
        @NotBlank private String safetyPhone;
        @NotBlank @Size(max=2000) private String content;
        private boolean entrusted;
        @Size(max=200) private List<DecorationModels.Worker> workers = new ArrayList<>();
    }
    @Getter @Setter public static class ExtendPermit extends Action { @Min(1) @Max(120) private int months; }
    @Getter @Setter public static class Acceptance extends Action {
        @Min(1) @Max(2) private int type;
        @Size(max=30) private List<String> assigneeIds = new ArrayList<>();
    }
    /** 申请人选择的验收日期；指派人员的动作不修改已经提交的申请。 */
    @Getter @Setter public static class AcceptanceRequest extends Acceptance {
        @NotNull @Min(1) private Long acceptanceDate;
    }
    @Getter @Setter public static class Feedback extends Action {
        @NotBlank private String taskId;
        private boolean passed;
        @Valid @Size(max=200) private List<DecorationModels.CheckResult> results = new ArrayList<>();
        @Size(max=20) private List<String> fileIds = new ArrayList<>();
        private Long deadline;
        @Size(max=200) private List<DecorationModels.Violation> violations = new ArrayList<>();
    }
    @Getter @Setter public static class Inspection extends Action {
        @NotNull @Size(min=1,max=30) @Valid private List<DecorationModels.InspectionRole> inspectionRoles = new ArrayList<>();
        @NotBlank private String reviewRoleId;
        @NotNull private Long startTime;
        @NotNull private Long endTime;
    }
    @Getter @Setter public static class Rectification extends Action {
        @NotBlank private String taskId;
        private boolean passed;
        private String reviewRoleId;
        @Size(max=20) private List<String> fileIds = new ArrayList<>();
    }
    @Getter @Setter public static class Finance extends Action {
        @Size(max=128) private String reference;
    }
    @Getter @Setter public static class Config {
        private String id;
        @NotBlank private String projectId;
        @Pattern(regexp="agreement|materials|checks|validity") @NotNull private String kind;
        @NotBlank @Size(max=128) private String name;
        @Size(max=64) private String houseType = "";
        @Size(max=64) private String decorationType = "";
        @Size(max=64) private String scene = "";
        private boolean enabled = true;
        @Min(0) private long version;
        @NotNull private DecorationModels.Configuration content;
    }
}
