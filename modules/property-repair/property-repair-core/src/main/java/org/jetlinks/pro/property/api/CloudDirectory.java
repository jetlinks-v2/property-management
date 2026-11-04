package org.jetlinks.pro.property.api;

import reactor.core.publisher.Flux;
/** 共享基础数据层读取 Cloud 权威资料的契约；调用链保留当前认证与租户上下文。 */
public interface CloudDirectory {
 Flux<CloudDirectoryRecord> organizations(String tenantId);
 Flux<CloudDirectoryRecord> persons(String tenantId);
 Flux<CloudDirectoryRecord> spaces(String tenantId);
}
