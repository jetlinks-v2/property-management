package org.jetlinks.pro.property.facility.service;

/** 仅暴露运行统计，不输出账号、凭据或告警内容。 */
public interface FacilitySaasAlarmsMBean {
  boolean isRunning();
  boolean isPolling();
  long getCompletedTime();
  long getFailureCount();
  long getProcessedCount();
  int getSubscriptionCount();
  String getLastError();
}
