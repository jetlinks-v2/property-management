package org.jetlinks.pro.smartpark.repair.service;

import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.math.RoundingMode;

/** 报表按业务项目聚合；计数和金额由数据库返回，比例使用旧系统分母。 */
@Getter @Setter
public class RepairReportRow {
    private String projectId;
    private String projectName;
    private long total, noDistributed, waitAccept, waitFollow, handling, waitCallback, waitAudit;
    private long archived, finished, close, abandoned;
    private long accepted, completed, timelyAccepted, withDeadline, timelyCompleted;
    private long ownerEvaluated, ownerPositive, paidCount;
    private long range1, range2, range3, range4, range5;
    private long follow1, follow2, follow3, follow4;
    private long paidReviews, paidPositive;
    /** 从报事到完成逐条截取整数分钟后平均；未完成记录不参与分母。 */
    private BigDecimal averageProcessingMinutes = BigDecimal.ZERO;
    private BigDecimal materialAmount = BigDecimal.ZERO, artificialAmount = BigDecimal.ZERO;
    private BigDecimal totalAmount = BigDecimal.ZERO, personAmount = BigDecimal.ZERO;
    private BigDecimal providerAmount = BigDecimal.ZERO, propertyAmount = BigDecimal.ZERO;

    public BigDecimal getNoDistributedRate(){return rate(noDistributed,total);}
    public BigDecimal getOrderRate(){return rate(accepted,total-noDistributed);}
    public BigDecimal getAbandonedRate(){return rate(abandoned,total);}
    public BigDecimal getReportCompletionRate(){return rate(completed,total-abandoned);}
    public BigDecimal getOrderGrabbingRate(){return rate(timelyAccepted,accepted);}
    public BigDecimal getTimelyCompletionRate(){return rate(timelyCompleted,withDeadline);}
    public BigDecimal getOwnerEvaluationRate(){return rate(ownerEvaluated,completed);}
    public BigDecimal getPositiveReviewRate(){return rate(ownerPositive,ownerEvaluated);}
    // 旧有偿报表好评阈值为3分，普通报表的业主好评阈值为4分，两者不能合并。
    public BigDecimal getPaidPositiveRate(){return rate(paidPositive,paidReviews);}
    public BigDecimal getCommissionAmount(){return personAmount.add(providerAmount);}
    static BigDecimal rate(long numerator,long denominator){
        return denominator==0?BigDecimal.ZERO:BigDecimal.valueOf(numerator)
            .multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(denominator),2,RoundingMode.HALF_UP);
    }
}
