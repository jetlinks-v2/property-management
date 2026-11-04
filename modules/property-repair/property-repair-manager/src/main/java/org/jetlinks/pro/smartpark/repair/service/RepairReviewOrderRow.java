package org.jetlinks.pro.smartpark.repair.service;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
@Getter @Setter
public class RepairReviewOrderRow extends RepairOrderEntity {
 private String reviewId, reviewKind, reviewReason, reviewApplicantName, reviewReviewerName, reviewOpinion;
 private Integer reviewStatus;
 private Long reviewCreateTime, reviewTime;
}
