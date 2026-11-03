package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolPointEntity;
import org.springframework.stereotype.Service;

/** 巡更点位持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolPointService extends GenericReactiveCrudService<PatrolPointEntity, String> {
    public PatrolPointService(ReactiveRepository<PatrolPointEntity, String> repository) {
        super(repository);
    }
}
