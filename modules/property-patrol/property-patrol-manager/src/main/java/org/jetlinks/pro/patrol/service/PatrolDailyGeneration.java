package org.jetlinks.pro.patrol.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.time.LocalDate;
import java.time.ZoneId;

/** 与原巡更相同，每日 00:01 生成当日任务；多节点重复执行由生成封印的主键防重。 */
@Component
@EnableScheduling
@AllArgsConstructor
@Slf4j
public class PatrolDailyGeneration {
    private final PatrolPlanService plans;
    private final PatrolTaskGenerator generator;

    @Scheduled(cron = "0 1 0 * * *", zone = "Asia/Shanghai")
    public Mono<Void> daily() {
        // Spring 管理响应式订阅生命周期；每次订阅重新读取日期，不在构造时固定日期。
        return Mono.defer(() -> generate(LocalDate.now(ZoneId.of("Asia/Shanghai"))));
    }

    Mono<Void> generate(LocalDate date) {
        // 这是服务自有数据的后台扫描，不使用用户查询入口；串行消费，避免收集全部计划。
        return plans.createQuery().where(PatrolPlanEntity::isEnabled, true)
                .and(PatrolPlanEntity::getEffectiveFrom, "lte", date)
                .and(PatrolPlanEntity::getEffectiveTo, "gte", date)
                .fetch().limitRate(128)
                .concatMap(plan -> generator.generateScheduled(plan, date)
                        .doOnError(error -> log.error("Patrol generation failed: plan={}, date={}", plan.getId(), date, error))
                        .onErrorResume(error -> Mono.empty()), 1)
                .then().as(MonoTracer.create("/patrol/tasks/daily"));
    }
}
