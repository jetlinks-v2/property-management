package org.jetlinks.pro.patrol.domain;

import java.time.Instant;

/** 执行资格只表达业务规则；身份、应用范围和资产权限由服务入口提供可信事实。 */
public final class ExecutionPolicy {
    private ExecutionPolicy() {}

    public enum RosterPolicy {
        optional,
        required
    }

    public static void check(
            Instant now,
            Instant start,
            Instant end,
            int completed,
            int required,
            Instant lastExecution,
            int cooldownMinutes,
            boolean assigned,
            RosterPolicy rosterPolicy,
            boolean rosterConfigured,
            boolean inShift) {
        if (!assigned) fail("error.patrol.execution.unassigned");
        // 与原巡更一致：当天无排班不限制执行；已持久化的 required 不再强制要求创建排班。
        if (rosterConfigured && !inShift) fail("error.patrol.execution.off_shift");
        if (now.isBefore(start) || !now.isBefore(end))
            fail("error.patrol.execution.outside_window");
        if (completed >= required) fail("error.patrol.execution.completed");
        if (lastExecution != null && now.isBefore(lastExecution.plusSeconds(cooldownMinutes * 60L)))
            fail("error.patrol.execution.cooldown");
    }

    private static void fail(String code) {
        throw new IllegalArgumentException(code);
    }
}
