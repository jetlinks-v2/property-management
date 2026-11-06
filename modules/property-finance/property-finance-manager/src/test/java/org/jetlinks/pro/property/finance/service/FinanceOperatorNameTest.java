package org.jetlinks.pro.property.finance.service;

import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.authorization.simple.SimpleAuthentication;
import org.hswebframework.web.authorization.simple.SimpleUser;
import org.jetlinks.pro.property.finance.entity.PaymentEntity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 姓名用于展示快照，用户 ID 用于身份；缺失姓名才保留账号。 */
class FinanceOperatorNameTest {
    static Authentication actor(String name) {
        var user = new SimpleUser();
        user.setId("operator-id");
        user.setUsername("gongchao");
        user.setName(name);
        var auth = new SimpleAuthentication();
        auth.setUser(user);
        return auth;
    }

    @Test
    void newFinancialRecordKeepsNameAndStableIdentity() {
        var row = new PaymentEntity();
        InvoiceCompanyService.init(row, actor("龚超"));
        assertEquals("龚超", row.getModifierName());
        assertEquals("operator-id", row.getModifierId());
        assertEquals(0L, row.getVersion());
        assertFalse(row.getDeleted());
    }

    @Test
    void missingOrBlankNameFallsBackToAccount() {
        for (String name : new String[] {null, "", "  "}) {
            var row = new PaymentEntity();
            InvoiceCompanyService.init(row, actor(name));
            assertEquals("gongchao", row.getModifierName());
            assertEquals("operator-id", row.getModifierId());
        }
    }
}
