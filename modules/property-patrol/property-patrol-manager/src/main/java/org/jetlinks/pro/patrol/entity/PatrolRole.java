package org.jetlinks.pro.patrol.entity;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

/** 认证服务返回的当前应用角色投影；不是持久化实体，没有独立角色表和成员写入入口。 */
@Getter
@Setter
public class PatrolRole extends PatrolConfigEntity {
    private String applicationId;
    private List<String> userIds = List.of();
}
