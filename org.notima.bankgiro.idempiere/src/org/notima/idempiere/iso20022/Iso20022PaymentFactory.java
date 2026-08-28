package org.notima.idempiere.iso20022;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.SortedMap;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MBankAccount;
import org.compiere.model.MCurrency;
import org.compiere.model.MInvoice;
import org.compiere.model.MPayment;
import org.compiere.util.CLogger;
import org.compiere.util.Env;
import org.notima.bankgiro.adempiere.BGPaymentModelValidator;
import org.notima.bankgiro.adempiere.PaymentExtendedRecord;
import org.notima.bankgiro.adempiere.PaymentFactory;
import org.notima.bankgiro.adempiere.model.MLBSettings;

/**
 * Class for reading inbound reconciliation files (creating payments in the
 * system since they are confirmed that they have happened).
 *
 * Ported from the idempiere-3.1 branch. Only CAMT-54 is supported on this
 * branch (the idempiere-3.1 branch also reads CAMT-53).
 *
 * @author Daniel Tamm
 *
 */
public class Iso20022PaymentFactory extends PaymentFactory {

	public static final String PROP_RECEIVABLES_ONLY = "receivables_only";

	private CAMT54PaymentFactory camt54;

	public Iso20022PaymentFactory(SortedMap<String, MLBSettings> lbSettings,
			Properties ctx) {
		super(lbSettings, ctx);
	}

	/**
	 * Returns the number of transactions skipped because a payment already
	 * existed (Swish payments created at order time).
	 */
	public int getSkippedExistingCount() {
		return camt54!=null ? camt54.getSkippedExistingCount() : 0;
	}

	public CLogger getLogger() {
		return m_log;
	}

	public int getClientId() {
		return m_clientId;
	}

	/**
	 * If following property is set, only receivables are read
	 *
	 * receivables = true
	 *
	 *
	 */
	@Override
	protected List<PaymentExtendedRecord> getSourcePayments(Properties props)
			throws Exception {

		camt54 = new CAMT54PaymentFactory(this);

		if (camt54.initFileReading()) {
			return camt54.getSourcePayments(props);
		}

		throw new Exception("The file " + (getFile()!=null ? getFile().getName() : "")
				+ " could not be read as a CAMT-54 (camt.054.001.02) file.");

	}

	public MPayment createPayment(MBankAccount ba, MInvoice invoice,
			Date pmtDate, boolean isReceipt, double amount, String currency,
			String trxName) throws AdempiereException {

		// Create payment
		MPayment pmt = new MPayment(Env.getCtx(), 0, trxName);
		pmt.set_ValueOfColumn("AD_Client_ID", Integer.valueOf(m_clientId));
		pmt.setC_BankAccount_ID(ba.get_ID());
		if (invoice!=null) {
			pmt.setC_Invoice_ID(invoice.get_ID());
			pmt.setC_BPartner_ID(invoice.getC_BPartner_ID());
		} else {
			pmt.setC_BPartner_ID(m_unknownBPartnerId);
		}
		pmt.setC_DocType_ID(isReceipt); // AP Payment
		pmt.setPayAmt(BigDecimal.valueOf(amount));
		pmt.setC_Currency_ID(MCurrency.get(Env.getCtx(), currency).get_ID());
		pmt.setTenderType(BGPaymentModelValidator.TENDERTYPE_Bankgiro);
		pmt.setDateAcct(new Timestamp(pmtDate.getTime()));
		pmt.setDateTrx(new Timestamp(pmtDate.getTime()));
		pmt.setIsApproved(true);
		if (!isDryRun()) {
			pmt.saveEx(trxName);
		}

		return pmt;

	}

}
