package org.jetlinks.pro.property.base.service;

public interface UserSyncJobMBean {
    long getSuccessCount();

    long getFailureCount();

    long getLastSuccessTime();

    boolean isRunning();
}
