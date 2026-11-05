package org.jetlinks.pro.property.facility.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.facility.entity.NotificationEntity;
import org.springframework.stereotype.Service;

/** Notification响应式持久化；独立入口的权限和事务由领域服务拥有。 */
@Service
public class NotificationStore
  extends GenericReactiveCrudService<NotificationEntity, String> {}
