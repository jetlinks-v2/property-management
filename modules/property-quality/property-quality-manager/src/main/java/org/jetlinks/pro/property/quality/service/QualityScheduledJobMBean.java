package org.jetlinks.pro.property.quality.service;
/** 调度生命周期和有界执行计数。 */
public interface QualityScheduledJobMBean {boolean isRunning();boolean isPolling();long getGeneratedCount();long getFailureCount();long getLastCompletedTime();}
