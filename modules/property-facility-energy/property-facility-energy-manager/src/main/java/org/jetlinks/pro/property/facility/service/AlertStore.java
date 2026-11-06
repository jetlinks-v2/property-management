package org.jetlinks.pro.property.facility.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.facility.entity.AlertEntity;
import org.springframework.stereotype.Service;

/** Alert响应式持久化；独立入口的权限和事务由领域服务拥有。 */
@Service
public class AlertStore
  extends GenericReactiveCrudService<AlertEntity, String> {}
