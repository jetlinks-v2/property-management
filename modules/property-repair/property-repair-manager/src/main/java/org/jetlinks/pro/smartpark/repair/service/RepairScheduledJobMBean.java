package org.jetlinks.pro.smartpark.repair.service;

/** 调度只读观测，计数为本节点累计值，不公开工单或个人资料。 */
public interface RepairScheduledJobMBean {
    boolean isRunning();
    boolean isPolling();
    long getScanCount();
    long getChangedCount();
    long getFailureCount();
    long getLastCompletedTime();
    long getNotificationSentCount();
    long getNotificationFailureCount();
    /** 最近扫描到的待发数量，上限100；不是整库积压总数。 */
    long getNotificationPendingObserved();
    String getNotificationLastErrorType();
}
