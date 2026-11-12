package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.decoration.entity.DecorationNotificationEntity;
import org.springframework.stereotype.Service;

/** 收件箱与发件箱共享同一持久化记录，不从当前状态虚构历史通知。 */
@Service
public class DecorationNotificationRepository extends GenericReactiveCrudService<DecorationNotificationEntity,String> {}
