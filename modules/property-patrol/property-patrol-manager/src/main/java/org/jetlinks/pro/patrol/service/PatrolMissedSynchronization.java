package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** 周期补齐已截止时段的真实缺失次数；Spring 管理订阅，多节点由任务 CAS 封印防重。 */
@Component
@AllArgsConstructor
public class PatrolMissedSynchronization {
    private final PatrolMissedService missed;
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public Mono<Void> synchronize() { return Mono.defer(missed::synchronizeEnded); }
}
