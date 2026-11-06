package org.jetlinks.pro.property.finance.service;

/** 后台执行进度只暴露计数、时间与稳定错误键，不输出认证快照或数据库异常。 */
public interface MonthClosingJobMBean extends BillingJobMBean {
    boolean isRunning();
}
