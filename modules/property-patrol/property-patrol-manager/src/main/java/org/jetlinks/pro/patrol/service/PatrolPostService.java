package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolPostEntity;
import org.springframework.stereotype.Service;

/** 巡更岗位持久化。对外写入统一经过配置入口校验。 */
@Service
public class PatrolPostService extends GenericReactiveCrudService<PatrolPostEntity, String> {
    public PatrolPostService(ReactiveRepository<PatrolPostEntity, String> repository) {
        super(repository);
    }
}
