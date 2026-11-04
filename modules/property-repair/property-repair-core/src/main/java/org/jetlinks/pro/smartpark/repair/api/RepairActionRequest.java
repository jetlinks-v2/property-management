package org.jetlinks.pro.smartpark.repair.api;

import lombok.Getter;
import lombok.Setter;
import jakarta.validation.constraints.*;
import java.util.List;
/** 乐观版本控制与幂等请求标识；首次跟进保留旧流程的照片与等级要求。 */
@Getter @Setter
public class RepairActionRequest {
 @NotNull @Min(0) private Long version;
 @NotBlank @Size(max=64) private String requestId;
 @Size(max=64) private String assignedUserId;
 @Size(max=4000) private String description;
 @Size(max=64) private String levelId;
 @Size(max=20) private List<@NotBlank String> attachmentIds;
 private Boolean rework;
 @Size(max=64) private String reviewId;
 private Boolean approved;
 private Long delayUntil;
 @Size(max=2000) private String reason;
 @Size(max=2000) private String reply;
 @Size(max=64) private String categoryId;
 @Size(max=50) private List<@NotBlank String> labelIds;
 private Long satisfaction;
 @Size(max=64) private String satisfactionId;
 private String satisfactionName;
 private List<String> evaluationLabelNames;
 @Size(max=64) private String replyId;
 @Size(max=50) private List<@NotBlank String> evaluationLabelIds;
 @Size(max=32) private String callbackMethod;
 @Size(max=64) private String callbackResult;
 private Long callbackTime;
 @Size(max=128) private String callbackPerson;
 @DecimalMin("0.00") @Digits(integer=10,fraction=2) private java.math.BigDecimal materialAmount;
 @DecimalMin("0.00") @Digits(integer=10,fraction=2) private java.math.BigDecimal artificialAmount;
 @Size(max=64) private String signatureFileId;
 @Size(max=128) private String ownerName;
 @Size(max=64) private String ownerPhone;
}
