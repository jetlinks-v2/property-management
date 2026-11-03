package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.springframework.stereotype.Service;

/** 巡更计划持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolPlanService extends GenericReactiveCrudService<PatrolPlanEntity, String> {
    public PatrolPlanService(ReactiveRepository<PatrolPlanEntity, String> repository) {
        super(repository);
    }
}
