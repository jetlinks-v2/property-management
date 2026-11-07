package org.jetlinks.pro.property.finance.activity;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 直接创建活动的输入，不接受客户端提交名称快照、项目、审批状态或来源路径。 */
@Getter
@Setter
public class ActivityInput {
    private Long version;
    private String name;
    private String activityType;
    private String startDate;
    private String endDate;
    private String subjectId;
    private String standardId;
    private Boolean offsetArrears = false;
    private String offsetStartDate;
    private String offsetEndDate;
    private List<String> targetIds = List.of();
    private List<Tier> tiers = List.of();
    private List<Attachment> attachments = List.of();
    private String remark;

    public record Tier(Integer paidMonths, Integer giftMonths) {}

    public record Attachment(String fileId, String fileName) {}
}
