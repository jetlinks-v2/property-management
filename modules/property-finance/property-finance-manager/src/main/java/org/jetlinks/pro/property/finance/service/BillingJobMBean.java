package org.jetlinks.pro.property.finance.service;

/** 仅暴露任务健康统计；带租户权限的重试必须经过业务API。 */
public interface BillingJobMBean {
    boolean isRunning();

    long getSuccessCount();

    long getFailureCount();

    long getSkippedCount();

    long getLastSuccessTime();

    long getLastFailureTime();

    String getLastError();
}
