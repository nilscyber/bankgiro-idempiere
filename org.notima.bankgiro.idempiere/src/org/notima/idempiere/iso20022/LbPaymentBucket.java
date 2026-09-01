package org.notima.idempiere.iso20022;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.notima.bankgiro.adempiere.LbPaymentRow;

public class LbPaymentBucket implements Comparable<LbPaymentBucket> {

	private static DateFormat dfmt = new SimpleDateFormat("yyyyMMdd");
	
	private Integer 	bPartnerId;
	private String		payDateStr;
	private List<LbPaymentRow>	rows = new ArrayList<LbPaymentRow>();
	
	public LbPaymentBucket(Integer bpId, Date time) {
		bPartnerId = bpId;
		payDateStr = dfmt.format(time);
	}
	
	public static String createKeyFor(Integer bpId, Date time) {
		String timeStr = time!=null ? dfmt.format(time) : "0";
		return bpId.toString() + "-" + timeStr;
	}
	
	public String getKey() {
		return bPartnerId.toString() + "-" + payDateStr;
	}
	
	public Date getPayDate() throws ParseException {
		return dfmt.parse(payDateStr);
	}

	public void add(LbPaymentRow row) {
		rows.add(row);
	}

	public List<LbPaymentRow> getLbPaymentRows() {
		return rows;
	}
	
	@Override
	public int compareTo(LbPaymentBucket o) {
		return getKey().compareTo(o.getKey());
	}
	
	
}
