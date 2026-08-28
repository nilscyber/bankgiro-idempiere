package org.w3._2001.xmlschema;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.GregorianCalendar;

import javax.xml.bind.annotation.adapters.XmlAdapter;
import javax.xml.datatype.DatatypeFactory;
import javax.xml.datatype.XMLGregorianCalendar;

/**
 * Date adapter used by the generated CAMT-54 classes
 * (iso.std.iso._20022.tech.xsd.camt_054_001).
 *
 * The idempiere-3.1 version of this class delegates to
 * Iso20022FileFactory.parseISODate/printISODate. That factory (outbound PAIN)
 * is not ported to this branch, so the same conversion is done locally.
 */
public class Adapter1
    extends XmlAdapter<String, XMLGregorianCalendar>
{

    private static DateFormat df = new SimpleDateFormat("yyyy-MM-dd");
    private static DatatypeFactory dataTypeFactory;

    static {
        try {
            dataTypeFactory = DatatypeFactory.newInstance();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public XMLGregorianCalendar unmarshal(String value) {
        if (value==null || value.trim().length()==0) return null;
        GregorianCalendar cal = new GregorianCalendar();
        try {
            Date date = df.parse(value);
            cal.setTime(date);
            return dataTypeFactory.newXMLGregorianCalendar(cal);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public String marshal(XMLGregorianCalendar value) {
        if (value==null) return null;
        return df.format(value.toGregorianCalendar().getTime());
    }

}
