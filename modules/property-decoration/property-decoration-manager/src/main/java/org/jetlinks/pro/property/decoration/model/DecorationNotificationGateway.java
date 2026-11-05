package org.jetlinks.pro.property.decoration.model;

import reactor.core.publisher.Mono;

/** 消息提供方必须回执真实受理结果；任务创建不代表短信、APP或微信通知已经送达。 */
public interface DecorationNotificationGateway {
    record Notice(String requestId, String decorationId, String taskId, String applicationId, String tenantId,
                  String projectId, String recipientUserId, String event) {}
    record Receipt(String provider, String reference, boolean accepted) {}
    Mono<Receipt> send(Notice notice);
}
