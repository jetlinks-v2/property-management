package org.jetlinks.pro.property.facility.service;

/** 无凭据的有界后台运行观测。 */
public interface FacilityJobsMBean {
  boolean isRunning();
  boolean isPolling();
  long getLastCompletedTime();
  long getFailureCount();
  long getSyncCount();
  long getCollectionCount();
  int getActiveSubscriptions();
  boolean isMeterPreparing();
  long getMeterLastCompletedTime();
  long getMeterPreparedCount();
  long getMeterFailureCount();
  String getMeterLastError();
}
