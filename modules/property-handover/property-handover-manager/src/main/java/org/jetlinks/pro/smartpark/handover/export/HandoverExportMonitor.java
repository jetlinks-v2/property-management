package org.jetlinks.pro.smartpark.handover.export;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Component;

import java.lang.management.ManagementFactory;

import javax.management.*;

/** Low-cost local worker counters. Business retries remain behind the authorized HTTP API. */
@Component
@RequiredArgsConstructor
@Slf4j
public class HandoverExportMonitor {
    private final HandoverExportService service;
    private ObjectName name;

    public interface ExportMBean {
        int getActive();

        long getClaimed();

        long getCompleted();

        long getFailed();

        String getLastError();

        long getLeaseMillis();

        long getHeartbeatMillis();
    }

    @PostConstruct
    void register() {
        try {
            name = new ObjectName("org.jetlinks:type=HandoverExport,name=worker");
            var server = ManagementFactory.getPlatformMBeanServer();
            server.registerMBean(
                    new StandardMBean(
                            new ExportMBean() {
                                public int getActive() {
                                    return service.active();
                                }

                                public long getClaimed() {
                                    return service.claimed();
                                }

                                public long getCompleted() {
                                    return service.completed();
                                }

                                public long getFailed() {
                                    return service.failed();
                                }

                                public String getLastError() {
                                    return service.lastError();
                                }

                                public long getLeaseMillis() {
                                    return HandoverExportService.LEASE.toMillis();
                                }

                                public long getHeartbeatMillis() {
                                    return HandoverExportService.HEARTBEAT.toMillis();
                                }
                            },
                            ExportMBean.class),
                    name);
        } catch (Exception error) {
            name = null;
            log.warn("Cannot register handover export MBean", error);
        }
    }

    @PreDestroy
    void unregister() {
        if (name != null)
            try {
                ManagementFactory.getPlatformMBeanServer().unregisterMBean(name);
            } catch (Exception error) {
                log.warn("Cannot unregister handover export MBean", error);
            }
    }
}
