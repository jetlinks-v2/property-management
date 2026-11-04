package org.jetlinks.pro.property.base.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.base.user.PropertyEmployeeInfo;
import reactor.core.publisher.Mono;

/**
 * 只读取认证本人的有效员工身份，无 userId 入参，无需用户目录查询权限。
 * 非员工、停用或已删除返回空；匿名及基础服务故障直接传播，不退回应用成员即员工。
 */
public class GetCurrentPropertyEmployeeCommand extends AbstractCommand<
        Mono<PropertyEmployeeInfo>, GetCurrentPropertyEmployeeCommand> {
    public static final String SERVICE_ID = QueryPropertyEmployeePageCommand.SERVICE_ID;
}
