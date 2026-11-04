package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.crud.events.EntityCreatedEvent;
import org.jetlinks.pro.smartpark.repair.entity.RepairProcessEntity;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** 与不可变过程记录同一事务生成发件项；发送方只扫描已提交的行，回滚不会外泄通知。 */
@Component @AllArgsConstructor
public class RepairNotificationListener {
    private final RepairNotificationService notifications;
    @EventListener
    public void created(EntityCreatedEvent<RepairProcessEntity> event){
        event.async(Flux.fromIterable(event.getEntity()).concatMap(notifications::record).then());
    }
}
