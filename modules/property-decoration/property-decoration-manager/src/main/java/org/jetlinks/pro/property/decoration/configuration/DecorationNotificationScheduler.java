package org.jetlinks.pro.property.decoration.configuration;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.decoration.service.DecorationNotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Spring管理响应式调度生命周期；无内存消息队列，多实例由数据库租约竞争。 */
@Component @EnableScheduling @RequiredArgsConstructor
@ConditionalOnProperty(name="decoration.notifications.enabled",havingValue="true",matchIfMissing=true)
@ManagedResource(objectName="org.jetlinks:type=DecorationNotification,name=Delivery")
public class DecorationNotificationScheduler {
    private final DecorationNotificationService notifications;
    @Scheduled(fixedDelayString="${decoration.notifications.delay:5000}",initialDelayString="${decoration.notifications.initial-delay:10000}")
    public Mono<Void> tick(){return Mono.defer(notifications::poll);}
    @ManagedAttribute public long getSentCount(){return notifications.getSentCount();}
    @ManagedAttribute public long getFailureCount(){return notifications.getFailureCount();}
    @ManagedAttribute public long getPendingObserved(){return notifications.getPendingObserved();}
}
