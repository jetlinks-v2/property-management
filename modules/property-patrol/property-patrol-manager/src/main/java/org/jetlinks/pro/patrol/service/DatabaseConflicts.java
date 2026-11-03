package org.jetlinks.pro.patrol.service;

import io.r2dbc.spi.R2dbcException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.r2dbc.connection.ConnectionFactoryUtils;

/** 使用驱动异常的标准 SQLState 分类，只有唯一键冲突可以恢复幂等结果。 */
final class DatabaseConflicts {
    private DatabaseConflicts() {}

    static boolean isDuplicateKey(Throwable error) {
        return error instanceof R2dbcException databaseError
                && ConnectionFactoryUtils.convertR2dbcException(
                                "patrol persistence", null, databaseError)
                        instanceof DuplicateKeyException;
    }
}
