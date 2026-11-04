package org.jetlinks.pro.smartpark.repair.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.repair.entity.RepairNotificationEntity;
import org.springframework.stereotype.Service;

/** 仅内部发件队列使用，工单写入事务负责记录，集群调度负责发送。 */
@Service
public class RepairNotificationStore extends GenericReactiveCrudService<RepairNotificationEntity,String> {}
