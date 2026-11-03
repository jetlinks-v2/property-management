package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.patrol.entity.PatrolTaskEntity;
import org.springframework.stereotype.Service;

/** 巡更Task持久化；不暴露通用写入接口。 */
@Service
public class PatrolTaskService extends GenericReactiveCrudService<PatrolTaskEntity, String> {
    public PatrolTaskService(ReactiveRepository<PatrolTaskEntity, String> repository) {
        super(repository);
    }
}
