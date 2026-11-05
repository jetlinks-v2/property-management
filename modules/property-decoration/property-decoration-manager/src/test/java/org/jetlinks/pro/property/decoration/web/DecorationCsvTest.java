package org.jetlinks.pro.property.decoration.web;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class DecorationCsvTest {
 @Test void quoteAndFormulaSafety(){assertEquals("\"'=1+1\"",DecorationCsv.cell("=1+1"));assertEquals("\"a,\"\"b\"",DecorationCsv.cell("a,\"b"));}
}
