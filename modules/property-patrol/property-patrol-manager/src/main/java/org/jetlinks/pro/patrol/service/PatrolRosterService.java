package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.springframework.stereotype.Service;

/** 巡更排班持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolRosterService extends GenericReactiveCrudService<PatrolRosterEntity, String> {
    public PatrolRosterService(ReactiveRepository<PatrolRosterEntity, String> repository) {
        super(repository);
    }
}
