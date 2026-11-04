package org.jetlinks.pro.property.base.user;

import java.io.Serializable;
import java.util.List;

/** 有效员工候选；主键为 SaaS userId，不暴露业主资料或完整用户档案。 */
public record PropertyEmployeeInfo(String id, String name, String telephone,
                                   List<String> organizationIds) implements Serializable { }
