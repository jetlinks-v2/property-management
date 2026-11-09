package org.jetlinks.pro.property.finance.payment;

import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.property.finance.entity.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 业务单号与内部主键分离；复用平台雪花流水，跨节点发号，不依赖进程内日计数器。 */
public final class FinanceDocumentNumbers {
    private FinanceDocumentNumbers() {}

    /** 仅在新事实初始化时发号，不在实体构造/反序列化或预览中生成，保持预览摘要稳定。 */
    public static void assign(FinanceEntity entity) {
        if (entity instanceof ReceivableEntity bill && bill.getBillNo() == null)
            bill.setBillNo(next("YS"));
        else if (entity instanceof PaymentEntity payment && payment.getBillNo() == null)
            payment.setBillNo(next("TEMPORARY".equals(payment.getSourceType()) ? "LS" : "SS"));
        else if (entity instanceof RefundEntity refund && refund.getBillNo() == null)
            refund.setBillNo(next("TK"));
    }

    public static String next(String prefix) {
        return prefix
                + LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE)
                + IDGenerator.SNOW_FLAKE_STRING.generate();
    }
}
