package org.jetlinks.pro.property.finance.service;

/** 导入执行器运行状态，不暴露文件路径、用户授权或业务数据。 */
public interface FinanceImportQueueMBean {
    boolean isRunning();

    long getCompletedTasks();

    long getFailedTasks();
}
