package org.jetlinks.pro.property.decoration.configuration;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.decoration.service.DecorationInspectionGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicLong;

/** 无内存任务队列，启动和每分钟追赶持久化游标；关闭由Spring取消订阅。 */
@Component @EnableScheduling @RequiredArgsConstructor
@ConditionalOnProperty(name="decoration.inspection.scheduler.enabled", havingValue="true", matchIfMissing=true)
@ManagedResource(objectName="org.jetlinks:type=DecorationInspection,name=Generator")
public class DecorationInspectionScheduler {
    private final DecorationInspectionGenerator generator;
    private final AtomicLong generated = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private volatile long lastSuccessTime;

    @Scheduled(fixedDelayString="${decoration.inspection.scheduler.delay:60000}", initialDelayString="${decoration.inspection.scheduler.initial-delay:10000}")
    public Mono<Void> tick() {
        return Mono.defer(() -> generator.generateBatch(System.currentTimeMillis()))
            .doOnNext(count -> { generated.addAndGet(count); lastSuccessTime = System.currentTimeMillis(); })
            .doOnError(error -> failures.incrementAndGet()).then();
    }
    @ManagedAttribute public long getGeneratedCount() { return generated.get(); }
    @ManagedAttribute public long getFailureCount() { return failures.get(); }
    @ManagedAttribute public long getLastSuccessTime() { return lastSuccessTime; }
}
