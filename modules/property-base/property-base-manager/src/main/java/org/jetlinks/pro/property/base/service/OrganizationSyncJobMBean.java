package org.jetlinks.pro.property.base.service;

/** 后台同步的只读运行统计，不提供跨权限的写入或重置操作。 */
public interface OrganizationSyncJobMBean {
    boolean isRunning();

    long getSuccessCount();

    long getFailureCount();

    long getLastSuccessTime();
}
