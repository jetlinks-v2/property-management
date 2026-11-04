package org.jetlinks.pro.patrol.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/** 与 EasyORM 使用同一事务管理器；不创建第二个连接池或事务域。 */
@Configuration
public class PatrolConfiguration {
    @Bean
    @ConditionalOnMissingBean
    public TransactionalOperator patrolTransactions(ReactiveTransactionManager manager) {
        return TransactionalOperator.create(manager);
    }
}
