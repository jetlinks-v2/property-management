package org.jetlinks.pro.property.facility.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.facility.entity.FaultEntity;
import org.springframework.stereotype.Service;

/** Fault响应式持久化；独立入口的权限和事务由领域服务拥有。 */
@Service
public class FaultStore
  extends GenericReactiveCrudService<FaultEntity, String> {}
