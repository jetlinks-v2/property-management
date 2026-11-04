package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolLevelEntity;
import org.springframework.stereotype.Service;

/** 点位等级持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolLevelService
        extends GenericReactiveCrudService<PatrolLevelEntity, String> {
    public PatrolLevelService(ReactiveRepository<PatrolLevelEntity, String> repository) {
        super(repository);
    }
}
