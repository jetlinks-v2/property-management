package org.jetlinks.pro.property.decoration.model;

import reactor.core.publisher.Mono;

/** 项目章由公共档案/文件适配器提供，装修不拥有印章主档。
 * 实现必须保留调用者认证与资产上下文，经公开接口读取有权项目章及私有图片；
 * 禁止将客户端文件、任意URL或本机配置当作项目章。无提供方/无有效章时拒绝打印。 */
public interface DecorationProjectSealGateway {
    record Request(String applicationId, String tenantId, String projectId) {}
    record Seal(String applicationId, String tenantId, String projectId, String mediaType, byte[] content) {}
    /** 只读，不上传、不修改档案；空结果表示项目章尚未配置，不自动重试。 */
    Mono<Seal> read(Request request);
}
