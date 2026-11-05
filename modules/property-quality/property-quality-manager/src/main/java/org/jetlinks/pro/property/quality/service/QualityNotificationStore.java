package org.jetlinks.pro.property.quality.service;
import org.springframework.stereotype.Service;import org.hswebframework.web.crud.service.GenericReactiveCrudService;import org.jetlinks.pro.property.quality.entity.QualityNotificationEntity;
/** 后台通知队列仓储，不对外暴露通用CRUD。 */
@Service public class QualityNotificationStore extends GenericReactiveCrudService<QualityNotificationEntity,String>{}
