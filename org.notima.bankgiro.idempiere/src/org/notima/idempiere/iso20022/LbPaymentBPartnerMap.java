package org.notima.idempiere.iso20022;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.notima.bankgiro.adempiere.LbPaymentRow;

/**
 * Class that sorts suggested payments into buckets, one for each recipient and pay date.
 * 
 * @author Daniel Tamm
 *
 */
public class LbPaymentBPartnerMap {

	private Map<String, LbPaymentBucket> bpartnerBuckets = new TreeMap<String, LbPaymentBucket>();
	
	public LbPaymentBPartnerMap(List<LbPaymentRow> suggestedPayments) {	
		
		for (LbPaymentRow row : suggestedPayments) {
			addToBucket(row);
		}
		
	}
	
	public Collection<LbPaymentBucket> getBuckets() {
		return bpartnerBuckets.values();
	}
	
	private void addToBucket(LbPaymentRow row) {
		
		Integer bp = row.bpartner_ID != 0 ? row.bpartner_ID : row.getBpartner().get_ID();
		Date payDate = new Date(row.payDate.getTime());
		String key = LbPaymentBucket.createKeyFor(bp, payDate);
		LbPaymentBucket bucket = bpartnerBuckets.get(key);
		if (bucket==null) {
			bucket = new LbPaymentBucket(bp, payDate);
			bpartnerBuckets.put(bucket.getKey(), bucket);
		}
		bucket.add(row);
		
	}
	
}
