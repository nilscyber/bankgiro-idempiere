package org.notima.idempiere.iso20022;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.SortedMap;

import javax.swing.JOptionPane;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.Unmarshaller;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MBPartner;
import org.compiere.model.MBankAccount;
import org.compiere.model.MCurrency;
import org.compiere.model.MDocType;
import org.compiere.model.MInvoice;
import org.compiere.model.MOrder;
import org.compiere.model.MPayment;
import org.compiere.model.Query;
import org.compiere.process.DocAction;
import org.compiere.util.Env;
import org.compiere.util.Ini;
import org.compiere.util.Trx;
import org.notima.bankgiro.adempiere.PaymentExtendedRecord;
import org.notima.bankgiro.adempiere.PluginRegistry;
import org.notima.bankgiro.adempiere.model.MLBSettings;
import org.notima.bg.lb.LbPayment;

import iso.std.iso._20022.tech.xsd.camt_054_001.AccountIdentification4Choice;
import iso.std.iso._20022.tech.xsd.camt_054_001.AccountNotification2;
import iso.std.iso._20022.tech.xsd.camt_054_001.AccountSchemeName1Choice;
import iso.std.iso._20022.tech.xsd.camt_054_001.ActiveOrHistoricCurrencyAndAmount;
import iso.std.iso._20022.tech.xsd.camt_054_001.AmountAndCurrencyExchange3;
import iso.std.iso._20022.tech.xsd.camt_054_001.AmountAndCurrencyExchangeDetails3;
import iso.std.iso._20022.tech.xsd.camt_054_001.BankToCustomerDebitCreditNotificationV02;
import iso.std.iso._20022.tech.xsd.camt_054_001.CashAccount16;
import iso.std.iso._20022.tech.xsd.camt_054_001.CashAccount20;
import iso.std.iso._20022.tech.xsd.camt_054_001.CreditDebitCode;
import iso.std.iso._20022.tech.xsd.camt_054_001.DateAndDateTimeChoice;
import iso.std.iso._20022.tech.xsd.camt_054_001.DocumentCAMT54;
import iso.std.iso._20022.tech.xsd.camt_054_001.EntryDetails1;
import iso.std.iso._20022.tech.xsd.camt_054_001.EntryTransaction2;
import iso.std.iso._20022.tech.xsd.camt_054_001.GenericAccountIdentification1;
import iso.std.iso._20022.tech.xsd.camt_054_001.PartyIdentification32;
import iso.std.iso._20022.tech.xsd.camt_054_001.ProprietaryReference1;
import iso.std.iso._20022.tech.xsd.camt_054_001.ReportEntry2;
import iso.std.iso._20022.tech.xsd.camt_054_001.StructuredRemittanceInformation7;
import iso.std.iso._20022.tech.xsd.camt_054_001.TransactionParty2;
import iso.std.iso._20022.tech.xsd.camt_054_001.TransactionReferences2;

/**
 * Reads CAMT-54 (camt.054.001.02) files with incoming payments.
 *
 * Ported from the idempiere-3.1 branch (org.jfree.util.Log replaced with
 * CLogger, message prefix constant moved to Iso20022Settings).
 */
public class CAMT54PaymentFactory {

	private Iso20022PaymentFactory				paymentFactory;
	private List<PaymentExtendedRecord> result = new ArrayList<PaymentExtendedRecord>();
	boolean receivablesOnly = false;
	private SortedMap<String, MLBSettings> lbSettings;
	private MLBSettings msgPrefix;
	private MBankAccount ba = null;
	private String	msgPrefixStr;
	private String	trxName;
	private DocumentCAMT54 document = new DocumentCAMT54();
	private CreditDebitCode crDbCode;
	private DateAndDateTimeChoice dte;
	private boolean realtimeEntry;
	private int skippedExisting = 0;

	private File f;

	public CAMT54PaymentFactory(Iso20022PaymentFactory pf) {
		paymentFactory = pf;
	}


	/**
	 * Initializes the file reading. If the file format is wrong, false is returned.
	 *
	 * @return
	 */
	public boolean initFileReading() throws Exception {

		f = paymentFactory.getFile();

		paymentFactory.getLogger().fine("Reading file " + f.getAbsolutePath());


		// Get message id prefix
		lbSettings = PluginRegistry.registry
				.getLbSettings();
		msgPrefix = lbSettings
				.get(Iso20022Settings.ISO20022_MSGPREFIX);
		if (msgPrefix == null)
			throw new Exception(
					"No ISO Message prefix configured. Check LB-settings");
		msgPrefixStr = msgPrefix.getName();

		// Correct for Handelsbanken sending files in non UTF-8 format
		Charset cs = Charset.forName("UTF-8");
		// SEB sends the files with a UTF-8 BOM, which the XML parser refuses
		// when it arrives as a character ("content is not allowed in prolog"),
		// so skip it if present.
		PushbackReader reader = new PushbackReader(new InputStreamReader(
				new FileInputStream(f), cs), 1);
		int firstChar = reader.read();
		if (firstChar != 0xFEFF && firstChar != -1) {
			reader.unread(firstChar);
		}

		try {
			JAXBContext contextObj = JAXBContext.newInstance(DocumentCAMT54.class);
			Unmarshaller marshallerObj = contextObj.createUnmarshaller();
			document = (DocumentCAMT54) marshallerObj.unmarshal(reader);
			reader.close();
		} catch (javax.xml.bind.UnmarshalException eu) {
			paymentFactory.getLogger().info(eu.getMessage());
			reader.close();
			return false;
		}

		return true;

	}

	/**
	 * Returns true if this file notifies about debits (our outgoing payments)
	 * rather than credits (incoming customer payments). SEB marks the group
	 * header with /DEBT/ or /CRED/.
	 */
	public boolean isDebitNotification() {
		String addtlInf = document.getBkToCstmrDbtCdtNtfctn().getGrpHdr()!=null
				? document.getBkToCstmrDbtCdtNtfctn().getGrpHdr().getAddtlInf() : null;
		if (addtlInf!=null) {
			if (addtlInf.contains("DEBT")) return true;
			if (addtlInf.contains("CRED")) return false;
		}
		// Fallback: look at the first entry
		for (AccountNotification2 s : document.getBkToCstmrDbtCdtNtfctn().getNtfctn()) {
			for (ReportEntry2 e : s.getNtry()) {
				return CreditDebitCode.DBIT.equals(e.getCdtDbtInd());
			}
		}
		return false;
	}

	/**
	 * Reconciles a debit notification: our payments to vendors, created with
	 * the outbound PAIN flow. Modeled on CreateLbPayments: each structured
	 * remittance block (one per vendor invoice, matching the outbound
	 * bucketing) is matched to the vendor invoice via its reference (OCR /
	 * BPDocumentNo / our document no), an AP payment is created against it
	 * inside the transaction and the invoice's LbStatus is set to PAID.
	 * Unmatched blocks become records without payment in the not-processed
	 * list - no unknown-payer payments are ever created.
	 */
	public List<PaymentExtendedRecord> createDebitPayments(Properties props) throws Exception {

		String localTrxName = Trx.createTrxName();
		Trx trx = Trx.get(localTrxName, true);
		trx.start();

		List<PaymentExtendedRecord> records = new ArrayList<PaymentExtendedRecord>();
		List<MPayment> paymentsToProcess = new ArrayList<MPayment>();
		Properties ctx = Env.getCtx();

		try {

			for (AccountNotification2 s : document.getBkToCstmrDbtCdtNtfctn().getNtfctn()) {

				ba = lookupBankAccount(s.getAcct());
				paymentFactory.setBankAccount(ba);

				for (ReportEntry2 e : s.getNtry()) {

					if (CreditDebitCode.CRDT.equals(e.getCdtDbtInd())) {
						paymentFactory.getLogger().warning(
								"Credit entry " + e.getNtryRef() + " in a debit notification - skipped.");
						continue;
					}

					dte = e.getBookgDt()!=null ? e.getBookgDt() : e.getValDt();
					Date trxDate = dte.getDt().toGregorianCalendar().getTime();
					paymentFactory.setTrxDate(trxDate);

					for (EntryDetails1 det : e.getNtryDtls()) {
						for (EntryTransaction2 ee : det.getTxDtls()) {
							processDebitTransaction(ee, trxDate, ctx, localTrxName,
									records, paymentsToProcess);
						}
					}
				}
			}

			// The same safety valves as the standard flow: if most of the file
			// could not be matched, save nothing.
			int notProcessed = 0;
			for (PaymentExtendedRecord r : records) {
				if (r.getAdempierePayment()==null) notProcessed++;
			}
			int processed = records.size() - notProcessed;
			if (processed < notProcessed && records.size() > 2) {
				if (Ini.isClient()) {
					JOptionPane.showMessageDialog(null,
							"The number of payments to process is smaller than the number of not processed payments. Setting to dry run (nothing is saved)");
				}
				paymentFactory.setDryRun(true);
			}

			if (!paymentFactory.isDryRun()) {
				trx.commit();
			} else {
				trx.rollback();
				for (PaymentExtendedRecord r : records) {
					r.setProcessed(true);
				}
			}

		} catch (Exception ex) {
			trx.rollback();
			trx.close();
			throw ex;
		}

		// Complete the matched payments after the successful commit
		if (!paymentFactory.isDryRun()) {
			trx.start();
			for (MPayment pmt : paymentsToProcess) {
				try {
					if (pmt.processIt(DocAction.ACTION_Complete)) {
						pmt.saveEx(localTrxName);
					}
				} catch (Exception ee) {
					paymentFactory.getLogger().warning(
							"Could not complete payment " + pmt.getDocumentNo() + " : " + ee.getMessage());
				}
			}
			trx.commit();
		}
		trx.close();

		for (PaymentExtendedRecord r : records) {
			if (r.getAdempierePayment()==null) {
				paymentFactory.addNotProcessed(r);
			}
		}

		return records;
	}

	private void processDebitTransaction(EntryTransaction2 ee, Date trxDate, Properties ctx,
			String localTrxName, List<PaymentExtendedRecord> records, List<MPayment> paymentsToProcess) {

		String cdtrName = null;
		if (ee.getRltdPties()!=null && ee.getRltdPties().getCdtr()!=null) {
			cdtrName = ee.getRltdPties().getCdtr().getNm();
		}
		String endToEndId = ee.getRefs()!=null ? ee.getRefs().getEndToEndId() : null;
		String txCcy = ee.getAmtDtls().getTxAmt().getAmt().getCcy();

		List<StructuredRemittanceInformation7> strds =
				ee.getRmtInf()!=null ? ee.getRmtInf().getStrd() : null;

		if (strds==null || strds.isEmpty()) {
			// No references at all (bank charges, manual transfers): report
			// only, never guess.
			PaymentExtendedRecord rec = newDebitRecord(cdtrName, txCcy, trxDate);
			rec.setOrderSum(ee.getAmtDtls().getTxAmt().getAmt().getValue().doubleValue());
			String ustrd = (ee.getRmtInf()!=null && !ee.getRmtInf().getUstrd().isEmpty())
					? ee.getRmtInf().getUstrd().get(0) : endToEndId;
			rec.setDescription((cdtrName!=null ? cdtrName + " : " : "") + (ustrd!=null ? ustrd : ""));
			records.add(rec);
			return;
		}

		for (StructuredRemittanceInformation7 r : strds) {

			boolean isOcr = isOCRReference(r);
			String ref = cleanReference(
					r.getCdtrRefInf()!=null && r.getCdtrRefInf().getRef()!=null
						? r.getCdtrRefInf().getRef()
						: (!r.getRfrdDocInf().isEmpty() ? r.getRfrdDocInf().get(0).getNb() : null));

			BigDecimal amount = null;
			if (r.getRfrdDocAmt()!=null) {
				if (r.getRfrdDocAmt().getRmtdAmt()!=null) {
					amount = r.getRfrdDocAmt().getRmtdAmt().getValue();
				} else if (r.getRfrdDocAmt().getCdtNoteAmt()!=null) {
					amount = r.getRfrdDocAmt().getCdtNoteAmt().getValue().negate();
				}
			}

			PaymentExtendedRecord rec = newDebitRecord(cdtrName, txCcy, trxDate);
			if (amount!=null) rec.setOrderSum(amount.doubleValue());
			rec.setBpInvoiceNo(ref);
			rec.setDescription((cdtrName!=null ? cdtrName + " : " : "") + (ref!=null ? ref : ""));

			MInvoice invoice = (ref!=null && amount!=null)
					? matchVendorInvoice(ctx, ref, endToEndId, amount, localTrxName) : null;

			if (invoice==null) {
				records.add(rec);
				continue;
			}

			try {
				double amt = Math.round(amount.doubleValue()*100)/100.0;

				invoice.set_ValueOfColumn("LbStatus",
						Integer.valueOf(org.notima.bankgiro.adempiere.LbPaymentRow.PAYSTATUS_PAID));
				invoice.saveEx(localTrxName);
				rec.setInvoice(invoice);
				rec.setInvoiceNo(invoice.getDocumentNo());
				rec.setBPartner(new MBPartner(ctx, invoice.getC_BPartner_ID(), localTrxName));

				MPayment payment = new MPayment(ctx, 0, localTrxName);
				payment.set_ValueOfColumn("AD_Client_ID", Integer.valueOf(paymentFactory.getClientId()));
				payment.setAD_Org_ID(paymentFactory.getOrgId());
				payment.setC_DocType_ID(false);		// AP Payment
				payment.setC_BankAccount_ID(ba.get_ID());
				payment.setC_BPartner_ID(invoice.getC_BPartner_ID());
				payment.setC_Currency_ID(MCurrency.get(ctx, txCcy).get_ID());
				if (isOcr && ref!=null) {
					payment.set_CustomColumn("OCR", ref);
				}
				payment.setTenderType(org.notima.bankgiro.adempiere.BGPaymentModelValidator.TENDERTYPE_Bankgiro);
				payment.setDescription((cdtrName!=null ? cdtrName + " : " : "") + (ref!=null ? ref : ""));
				payment.setDateAcct(new Timestamp(trxDate.getTime()));
				payment.setDateTrx(new Timestamp(trxDate.getTime()));
				payment.setAmount(payment.getC_Currency_ID(), new BigDecimal(Double.toString(amt)));
				payment.setC_Invoice_ID(invoice.get_ID());
				payment.set_CustomColumn("IsOCR", invoice.get_Value("IsOCR"));
				double openAmount = invoice.getOpenAmt().doubleValue();
				double pmtDiff = openAmount - amt;
				if (pmtDiff!=0) {
					if (Math.abs(pmtDiff) > paymentFactory.getAmountThreshold()) {
						payment.setOverUnderAmt(new BigDecimal(Double.toString(pmtDiff)));
					} else {
						payment.setDiscountAmt(new BigDecimal(Double.toString(pmtDiff)));
					}
				}
				payment.setIsApproved(true);
				payment.saveEx(localTrxName);
				rec.setAdempierePayment(payment);
				rec.setBPartnerIdentified(true);

				if (paymentFactory.isAutoCompleteEnabled() && !payment.isOverUnderPayment()) {
					paymentsToProcess.add(payment);
				}
			} catch (Exception ex) {
				paymentFactory.getLogger().warning("Could not create payment for "
						+ (cdtrName!=null ? cdtrName : "?") + " ref " + ref + ": " + ex.getMessage());
				rec.setAdempierePayment(null);
			}

			records.add(rec);
		}
	}

	private PaymentExtendedRecord newDebitRecord(String cdtrName, String ccy, Date trxDate) {
		PaymentExtendedRecord rec = new PaymentExtendedRecord();
		rec.setBankAccountPtr(ba);
		rec.setName(cdtrName);
		rec.setCurrency(ccy);
		rec.setTrxDate(trxDate);
		LbPayment lbPayment = new LbPayment();
		lbPayment.setDstName(cdtrName);
		rec.setTransaction(lbPayment);
		return rec;
	}

	/**
	 * Finds the vendor invoice a remittance block refers to. The reference is
	 * the vendor's own invoice number (BPDocumentNo), the OCR, or - via the
	 * transaction's EndToEndId - our document number. Ambiguities are resolved
	 * by preferring invoices in transit (LbStatus=2) and matching open amount;
	 * if still ambiguous, no match is returned.
	 */
	private MInvoice matchVendorInvoice(Properties ctx, String ref, String endToEndId,
			BigDecimal amount, String localTrxName) {

		List<MInvoice> candidates = new Query(ctx, MInvoice.Table_Name,
				"AD_Client_ID=? AND IsSoTrx='N' AND DocStatus IN ('CO','CL') AND IsPaid='N'"
				+ " AND (OCR=? OR BPDocumentNo=? OR DocumentNo=? OR DocumentNo=?)",
				localTrxName)
				.setParameters(new Object[]{ Env.getAD_Client_ID(ctx), ref, ref, ref,
						endToEndId!=null ? endToEndId : ref })
				.list();

		if (candidates.isEmpty()) return null;
		if (candidates.size()==1) return candidates.get(0);

		List<MInvoice> inTransit = new ArrayList<MInvoice>();
		for (MInvoice iv : candidates) {
			Object st = iv.get_Value("LbStatus");
			if (st!=null && ((Number)st).intValue()==org.notima.bankgiro.adempiere.LbPaymentRow.PAYSTATUS_INTRANSIT) {
				inTransit.add(iv);
			}
		}
		if (inTransit.size()==1) return inTransit.get(0);

		List<MInvoice> pool = inTransit.isEmpty() ? candidates : inTransit;
		MInvoice amountMatch = null;
		for (MInvoice iv : pool) {
			if (Math.abs(iv.getOpenAmt().doubleValue() - amount.doubleValue()) < 0.01) {
				if (amountMatch!=null) return null;	// still ambiguous
				amountMatch = iv;
			}
		}
		return amountMatch;
	}

	public List<PaymentExtendedRecord> getSourcePayments(Properties props)
			throws Exception {

		if (props!=null && props.containsKey(Iso20022PaymentFactory.PROP_RECEIVABLES_ONLY) && props.get(Iso20022PaymentFactory.PROP_RECEIVABLES_ONLY)!=null) {
			receivablesOnly = true;
		}


		// Get BankToCustomerStatement (can be many accounts)
		BankToCustomerDebitCreditNotificationV02 cct = document.getBkToCstmrDbtCdtNtfctn();

		// Get statement entries (one per bank account to be reconciled)
		List<AccountNotification2> statements = cct.getNtfctn();

		for (AccountNotification2 s : statements) {

			// Get bank account
			CashAccount20 acct = s.getAcct();
			ba = lookupBankAccount(acct);
			paymentFactory.setBankAccount(ba);

			// Entry count
			int entryCount = 0;

			// Get a list of entries
			List<ReportEntry2> entries = s.getNtry();
			for (ReportEntry2 e : entries) {
				entryCount++;
				try {
					processReportEntry(e);
				} catch (Exception ee) {
					paymentFactory.getLogger().warning("Can't process entry " + entryCount + " : " + ee.getMessage());
					ee.printStackTrace();
				}

			}
		}

		return result;
	}

	private void processReportEntry(ReportEntry2 e) {


		// Get indicator (debit = outgoing payments) / credit = incoming
		// payments
		crDbCode = e.getCdtDbtInd();

		// Swish transactions arrive as real-time credit transfers (bank
		// transaction code family RRCT).
		realtimeEntry = e.getBkTxCd()!=null && e.getBkTxCd().getDomn()!=null
				&& e.getBkTxCd().getDomn().getFmly()!=null
				&& "RRCT".equals(e.getBkTxCd().getDomn().getFmly().getCd());

		// Get book date
		dte = e.getBookgDt();
		if (dte==null) {
			dte = e.getValDt();
		}

		// Get entry details
		List<EntryDetails1> lst = e.getNtryDtls();

		// Process outgoing payments

		for (EntryDetails1 det : lst) {

			List<EntryTransaction2> entList = det.getTxDtls();

			for (EntryTransaction2 ee : entList) {

				processEntryTransaction(ee);

			}

		} // End of processing outgoing payments

	}

	private void setAmountFromTransaction(PaymentExtendedRecord rec, EntryTransaction2 ee) {

		AmountAndCurrencyExchange3 aace = ee.getAmtDtls();
		AmountAndCurrencyExchangeDetails3 aaced = aace
				.getTxAmt();
		ActiveOrHistoricCurrencyAndAmount amount = aaced
				.getAmt();

		rec.setCurrency(amount.getCcy());
		rec.setOrderSum(amount.getValue().doubleValue());

	}

	/**
	 * Sets the invoice reference in the PaymentExtendedRecord from EntryTransaction.
	 *
	 * If OCR the paymentReferenceField is used, otherwise invoiceNo.
	 *
	 * @param rec
	 * @param ee
	 */
	private void setInvoiceReferenceFromTransaction(PaymentExtendedRecord rec, EntryTransaction2 ee) {

		TransactionReferences2 tr2 = ee.getRefs();

		// Get our invoice no
		String ourRef = tr2!=null ? tr2.getEndToEndId() : null;
		ProprietaryReference1 theirRef1 = tr2!=null ? tr2.getPrtry() : null;
		if (theirRef1!=null) {
			String theirRef = theirRef1.getRef();
			rec.setBpInvoiceNo(theirRef);
		}

		if (ourRef==null) {
			// Look in structured remittance information. Walk ALL blocks until
			// a usable reference is found - SEB sends junk in some of them
			// ("REF MISSING", comma-doubled numbers) with the real reference
			// in a later block or in AddtlRmtInf. Transactions paying several
			// invoices are split before this method is called (see
			// splittableRemittance).
			if (ee.getRmtInf()!=null) {
				for (StructuredRemittanceInformation7 r : ee.getRmtInf().getStrd()) {
					String ref = extractReference(r);
					if (ref!=null) {
						if (isOCRReference(r)) {
							rec.setPaymentReference(ref);
						}
						rec.setInvoiceNo(ref);
						break;
					}
				}
			}
		} else {
			rec.setInvoiceNo(ourRef);
		}

	}

	/**
	 * Extracts a usable reference from one structured remittance block: the
	 * document/creditor reference if usable, otherwise a numeric AddtlRmtInf
	 * entry (e.g. Nb="REF MISSING" with the invoice number in AddtlRmtInf).
	 * References are plain invoice document numbers - no check digit is
	 * stripped.
	 */
	private String extractReference(StructuredRemittanceInformation7 r) {
		String ref = cleanReference(Iso20022Helper.getMostLikelyReference(r));
		if (isUsableReference(ref)) return ref;
		for (String addtl : r.getAddtlRmtInf()) {
			String cand = cleanReference(addtl);
			if (isUsableReference(cand)) return cand;
		}
		return null;
	}

	/**
	 * Normalizes a remittance reference: trims, and collapses comma-separated
	 * lists ("9269631,9269631") to a single token - the distinct value when
	 * all tokens are equal, otherwise the first token.
	 */
	private String cleanReference(String ref) {
		if (ref==null) return null;
		ref = ref.trim();
		if (ref.indexOf(',')<0) return ref;
		String first = null;
		boolean allEqual = true;
		for (String token : ref.split(",")) {
			token = token.trim();
			if (token.length()==0) continue;
			if (first==null) {
				first = token;
			} else if (!first.equals(token)) {
				allEqual = false;
			}
		}
		if (first!=null && !allEqual) {
			paymentFactory.getLogger().warning("Multiple references in \"" + ref + "\" - using " + first);
		}
		return first;
	}

	/**
	 * Returns true if the reference looks like something we can match on:
	 * digits only, reasonable length. Filters out junk like "REF MISSING".
	 */
	private boolean isUsableReference(String ref) {
		if (ref==null) return false;
		if (ref.length()<4 || ref.length()>20) return false;
		for (int i=0; i<ref.length(); i++) {
			if (!Character.isDigit(ref.charAt(i))) return false;
		}
		return true;
	}


	private void processEntryTransaction(EntryTransaction2 ee) {

		// One transfer can pay several invoices, each structured remittance
		// block carrying its own reference and amount. Split into one payment
		// record per invoice when every block has both and the amounts sum
		// exactly to the transaction amount; anything else is handled as a
		// single record.
		List<StructuredRemittanceInformation7> parts = splittableRemittance(ee);
		if (parts != null) {
			for (StructuredRemittanceInformation7 r : parts) {
				processTransactionPart(ee, extractReference(r), isOCRReference(r),
						r.getRfrdDocAmt().getRmtdAmt().getValue());
			}
			return;
		}

		processTransactionPart(ee, null, false, null);

	}

	/**
	 * Returns the structured remittance blocks of a transaction that pays
	 * several invoices at once, when it can be safely split: at least two
	 * blocks, every block with a usable reference and a remitted amount in
	 * the transaction currency, no credit notes, and the amounts summing
	 * exactly to the transaction amount. Otherwise null.
	 */
	private List<StructuredRemittanceInformation7> splittableRemittance(EntryTransaction2 ee) {

		if (ee.getRefs()!=null && ee.getRefs().getEndToEndId()!=null) return null;
		if (ee.getRmtInf()==null) return null;
		List<StructuredRemittanceInformation7> strds = ee.getRmtInf().getStrd();
		if (strds==null || strds.size()<2) return null;
		if (ee.getAmtDtls()==null || ee.getAmtDtls().getTxAmt()==null) return null;

		ActiveOrHistoricCurrencyAndAmount txAmt = ee.getAmtDtls().getTxAmt().getAmt();
		BigDecimal sum = BigDecimal.ZERO;
		for (StructuredRemittanceInformation7 r : strds) {
			if (extractReference(r)==null) return null;
			if (r.getRfrdDocAmt()==null || r.getRfrdDocAmt().getRmtdAmt()==null
					|| r.getRfrdDocAmt().getRmtdAmt().getValue()==null) return null;
			if (r.getRfrdDocAmt().getCdtNoteAmt()!=null) return null;
			String ccy = r.getRfrdDocAmt().getRmtdAmt().getCcy();
			if (ccy!=null && !ccy.equals(txAmt.getCcy())) return null;
			sum = sum.add(r.getRfrdDocAmt().getRmtdAmt().getValue());
		}
		if (sum.compareTo(txAmt.getValue())!=0) return null;

		return strds;
	}

	/**
	 * Creates one payment record for a transaction, or for one invoice's part
	 * of a multi-invoice transaction (partRef/partAmount set).
	 */
	private void processTransactionPart(EntryTransaction2 ee, String partRef,
			boolean partIsOcr, BigDecimal partAmount) {

		// This record is used to comply with previous jasper reports.
		LbPayment lbPayment = new LbPayment();

		PaymentExtendedRecord rec = new PaymentExtendedRecord();
		rec.setBankAccountPtr(ba);

		if (partRef != null) {
			rec.setInvoiceNo(partRef);
			if (partIsOcr) {
				rec.setPaymentReference(partRef);
			}
		} else {
			setInvoiceReferenceFromTransaction(rec, ee);
		}

		// Cyberphoto: web/Swish payments carry the order number as reference
		// rather than the invoice number. Setting it as order no as well lets
		// the fallback matching in PaymentFactory look up the invoice via the
		// order when no invoice matches the reference directly.
		if (rec.getInvoiceNo()!=null && rec.getInvoiceNo().trim().length()>0) {
			rec.setOrderNo(rec.getInvoiceNo());
		}

		if (partAmount != null) {
			rec.setCurrency(ee.getAmtDtls().getTxAmt().getAmt().getCcy());
			rec.setOrderSum(partAmount.doubleValue());
		} else {
			setAmountFromTransaction(rec, ee);
		}

		// Cyberphoto: Swish payments already have a completed payment created
		// at order time (se.cyberphoto.swish.SwishPaymentAddDetails, same bank
		// account, C_Order_ID set), so skip the transaction when that payment
		// exists to avoid creating a duplicate.
		if (realtimeEntry && existingPaymentForOrder(rec)) {
			skippedExisting++;
			return;
		}

		// Check invoice
		MInvoice invoice = new Query(
				Env.getCtx(),
				MInvoice.Table_Name,
				"AD_Client_ID=? AND DocumentNo=? AND IsSoTrx='Y'",
				trxName).setParameters(
				new Object[] {
						Env.getAD_Client_ID(Env.getCtx()),
						rec.getInvoiceNo() }).firstOnly();

		if (invoice != null) {
			rec.setInvoice(invoice);
			rec.setBPartner(new MBPartner(Env.getCtx(),
					invoice.getC_BPartner_ID(), trxName));
		} else {
			paymentFactory.getLogger().warning("Can't match invoice " + rec.getInvoiceNo());
		}

		rec.setDescription(rec.getInvoiceNo());
		rec.setTransaction(lbPayment);

		lbPayment.setAmount(rec.getOrderSum());
		rec.setTrxDate(dte.getDt().toGregorianCalendar()
				.getTime());
		// The last trx date will be trx date for the whole
		// file.
		paymentFactory.setTrxDate(rec.getTrxDate());

		TransactionParty2 trp = ee.getRltdPties();
		PartyIdentification32 pid = trp.getDbtr();
		if (pid!=null) {
			rec.setName(pid.getNm());
			lbPayment.setDstName(pid.getNm());
		}

		CashAccount16 rcptAcct = trp.getCdtrAcct();
		if (rcptAcct!=null) {
			AccountIdentification4Choice acctId = rcptAcct
					.getId();
			String rcptAcctStr;
			String iban = acctId.getIBAN();
			if (iban == null) {

				GenericAccountIdentification1 ai = acctId
						.getOthr();
				rcptAcctStr = ai.getId();

			} else {
				rcptAcctStr = iban;
			}
			rec.setBankAccount(rcptAcctStr);
		}

		boolean arCreditMemo = false;
		// Check document type
		if (invoice != null) {
			arCreditMemo = MDocType.DOCBASETYPE_ARCreditMemo
					.equalsIgnoreCase(invoice
							.getC_DocType()
							.getDocBaseType());
		}

		// Create payment
		if (!arCreditMemo && !CreditDebitCode.CRDT.equals(crDbCode)) {
			try {
				MPayment pmt = paymentFactory.createPayment(ba, invoice,
						rec.getTrxDate(), false, rec.getOrderSum(),
						rec.getCurrency(), trxName);

				rec.setAdempierePayment(pmt);
			} catch (AdempiereException ae) {
				paymentFactory.getLogger().warning(ae.getMessage() + " invoice " + invoice.getDocumentNo());
				rec.setInvoice(invoice);
			}
		} else {
			// Create the payment later if credit memo
			rec.setInvoice(invoice);
		}

		result.add(rec);

	}


	/**
	 * Matches account information to the account in Adempiere.
	 *
	 * @param acct
	 * @return
	 */
	private MBankAccount lookupBankAccount(CashAccount20 acct) throws Exception {

		MBankAccount ba = null;

		AccountIdentification4Choice acctId = acct.getId();
		String iban = acctId.getIBAN();
		String id = null;
		// Check for iban match
		if (iban != null) {
			ba = new Query(Env.getCtx(), MBankAccount.Table_Name,
					"IBAN=? AND AD_Client_ID=?", null).setParameters(
					new Object[] { iban, Env.getAD_Client_ID(Env.getCtx()) })
					.first();
		}

		if (ba == null) {
			GenericAccountIdentification1 ai = acctId.getOthr();
			id = ai.getId();
			AccountSchemeName1Choice asc = ai.getSchmeNm();
			String schemeName = asc.getCd();

			if ("BBAN".equalsIgnoreCase(schemeName)) {
				ba = new Query(
						Env.getCtx(),
						MBankAccount.Table_Name,
						"translate(concat(bban, accountno), '-., ','')=? AND AD_Client_ID=?",
						null).setParameters(
						new Object[] { id, Env.getAD_Client_ID(Env.getCtx()) })
						.first();
			}

		}

		if (ba == null) {
			throw new Exception(
					"Can't find account " + iban != null ? ("IBAN: " + iban)
							: ("BBAN: " + id));
		}
		return ba;
	}


	/**
	 * Returns the number of transactions skipped because a payment already
	 * existed (Swish payments created at order time).
	 */
	public int getSkippedExistingCount() {
		return skippedExisting;
	}

	/**
	 * Returns true if a completed payment already exists on this bank account
	 * for the order and amount of this transaction.
	 */
	private boolean existingPaymentForOrder(PaymentExtendedRecord rec) {

		if (rec.getOrderNo()==null || rec.getOrderNo().trim().length()==0)
			return false;

		MOrder order = new Query(Env.getCtx(), MOrder.Table_Name,
				"AD_Client_ID=? AND DocumentNo=?", trxName)
				.setParameters(new Object[] {
						Env.getAD_Client_ID(Env.getCtx()), rec.getOrderNo() })
				.first();
		if (order==null)
			return false;

		MPayment existing = new Query(Env.getCtx(), MPayment.Table_Name,
				"C_Order_ID=? AND C_BankAccount_ID=? AND PayAmt=? AND IsReceipt='Y' AND DocStatus IN ('CO','CL')",
				trxName)
				.setParameters(new Object[] { order.get_ID(), ba.get_ID(),
						BigDecimal.valueOf(rec.getOrderSum()) })
				.first();

		if (existing!=null) {
			paymentFactory.getLogger().info("Skipping Swish transaction for order "
					+ rec.getOrderNo() + " (" + rec.getOrderSum() + " " + rec.getCurrency()
					+ "): payment " + existing.getDocumentNo() + " already exists.");
			return true;
		}

		return false;
	}


	private boolean isOCRReference(StructuredRemittanceInformation7 sri) {
		if (sri==null) return false;
		if (sri.getCdtrRefInf()==null) return false;
		if (sri.getCdtrRefInf().getTp()==null) return false;
		if (sri.getCdtrRefInf().getTp().getCdOrPrtry()==null) return false;
		if (sri.getCdtrRefInf().getTp().getCdOrPrtry().getCd()==null) return false;
		if (sri.getCdtrRefInf().getTp().getCdOrPrtry().getCd().name()==null) return false;
		return ("SCOR".equals(sri.getCdtrRefInf().getTp().getCdOrPrtry().getCd().name()));
	}


}
