package org.jetlinks.pro.property.decoration.integration;

import lombok.RequiredArgsConstructor;
import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.jetlinks.pro.property.decoration.entity.DecorationSimulationReceiptEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

/** 模拟外部服务独立提交回执；装修事务回滚/重试时仍返回同一结果，不触发真实资金或消息。 */
@Service
@RequiredArgsConstructor
public class SimulationReceiptWriter {
    private final ReactiveRepository<DecorationSimulationReceiptEntity, String> receipts;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Mono<Void> insert(DecorationSimulationReceiptEntity receipt) { return receipts.insert(receipt).then(); }
}
