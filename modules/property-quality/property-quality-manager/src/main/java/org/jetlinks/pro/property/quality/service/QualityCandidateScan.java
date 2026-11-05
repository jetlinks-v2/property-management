package org.jetlinks.pro.property.quality.service;

import org.jetlinks.pro.property.base.user.PropertyUserInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Function;

/** 候选人实时授权涉及远程调用，限制并发并保留目录顺序；拒绝与故障仍由原授权边界决定。 */
final class QualityCandidateScan {
    private QualityCandidateScan() {}

    static Flux<PropertyUserInfo> filter(Flux<PropertyUserInfo> people,
                                         Function<PropertyUserInfo, Mono<Boolean>> eligible) {
        // filterWhen 的 bufferSize 只控制预取，异步谓词仍串行；大租户不能逐人等待 RPC。
        return people.flatMapSequential(person -> eligible.apply(person)
            .filter(Boolean.TRUE::equals).map(ignored -> person), 32, 1);
    }
}
