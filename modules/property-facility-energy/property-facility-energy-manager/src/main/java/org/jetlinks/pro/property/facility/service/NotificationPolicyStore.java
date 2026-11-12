package org.jetlinks.pro.property.facility.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.facility.entity.NotificationPolicyEntity;
import org.springframework.stereotype.Service;

@Service
public class NotificationPolicyStore extends GenericReactiveCrudService<NotificationPolicyEntity, String> {}
