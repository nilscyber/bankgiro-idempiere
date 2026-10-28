package org.notima.idempiere.iso20022;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import org.compiere.model.MBPBankAccount;
import org.compiere.model.MBPartner;
import org.compiere.model.MBPartnerLocation;
import org.compiere.model.MBankAccount;
import org.compiere.model.MInvoice;
import org.compiere.model.MLocation;
import org.notima.bankgiro.adempiere.BankAccountUtil;
import org.notima.bankgiro.adempiere.LbPaymentRow;
import org.notima.bankgiro.adempiere.MessageCenter;
import org.notima.bankgiro.adempiere.PaymentFileFactory;
import org.notima.bankgiro.adempiere.PaymentValidator;
import org.notima.bg.BgUtil;

/**
 * Creates SEPA payment files in the CSV layout SEB's internet bank accepts
 * for file upload ("Betalningar via fil"). This is SEB's own template, not a
 * public standard: 36 Swedish-named columns that map one to one onto
 * pain.001 SEPA credit transfer fields. One row per vendor and pay date,
 * exactly like the PAIN factory groups its payments.
 *
 * The format constants (payment type, service level, decimal and date
 * format) are what the template suggests; they have not been confirmed
 * against an accepted upload yet. Columns we have no data for are left
 * empty.
 *
 * @see Iso20022FileFactory
 */
public class SebSepaCsvFileFactory implements PaymentFileFactory {

	public static String KEY = "SEB-SEPA-CSV";

	// Column 1
	static final String PAYMENT_TYPE = "sepa";
	// Column 7
	static final String SERVICE_LEVEL = "SEPA Standard";
	// Column 14 is limited to 140 characters by the SEPA scheme
	static final int MAX_MESSAGE_LENGTH = 140;

	static final String[] HEADER = {
		"Betaltyp", "Från konto", "Från konto BIC", "Till konto (IBAN)", "Belopp", "Betaldatum",
		"SEPA Standard/SEPA Instant", "Mottagarens namn", "Land", "Adress", "Husnummer", "Stad",
		"Postnummer", "Meddelande", "Creditor reference", "Egen anteckning", "Syfte med betalning",
		"Avsändarens referens", "Mottagarens id-typ", "Mottagarens id-nummer",
		"Mottagarens ID nummer: Organisation eller Personlig", "Slutliga mottagarens namn",
		"Slutliga mottagarens id-typ", "Slutliga mottagarens id-nummer",
		"Slutliga mottagarens ID nummer: Organisation eller Personlig", "Avsändarens id-typ",
		"Avsändarens id", "Avsändarens ID nummer: Organisation eller Personlig",
		"Ursprungliga avsändarens namn", "Ursprungliga avsändarens id-typ",
		"Ursprungliga avsändarens id-nummer",
		"Ursprungliga avsändarens ID nummer: Organisation eller Personlig",
		"Skatterättslig hemvist", "Skattekod", "Fondrelaterat syfte - kod", "Fondrelaterat syfte - text"
	};

	private static final DecimalFormat AMOUNT_FORMAT;
	static {
		DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
		AMOUNT_FORMAT = new DecimalFormat("0.00", symbols);
		AMOUNT_FORMAT.setGroupingUsed(false);
	}
	private static final String DATE_FORMAT = "yyyy-MM-dd";
	private static final String FILE_NAME_FORMAT = "'sepa_'yyyyMMdd_HHmmss'.csv'";

	@Override
	public String getKey() {
		return KEY;
	}

	@Override
	public String getName() {
		return "SEB SEPA CSV File Creator";
	}

	@Override
	public PaymentValidator getPaymentValidator() {
		return new SebSepaPaymentValidator();
	}

	@Override
	public File createPaymentFile(MBankAccount srcAccount, List<LbPaymentRow> suggestedPayments,
			File outDir, String trxName) {

		List<String[]> rows = new ArrayList<String[]>();
		try {
			LbPaymentBPartnerMap buckets = new LbPaymentBPartnerMap(suggestedPayments);
			for (LbPaymentBucket bucket : buckets.getBuckets()) {
				rows.add(createRow(srcAccount, bucket.getLbPaymentRows()));
			}
		} catch (Exception e) {
			MessageCenter.error(e.getMessage());
			e.printStackTrace();
			return null;
		}

		File outFile = null;
		try {
			outFile = new File(outDir, new SimpleDateFormat(FILE_NAME_FORMAT).format(new Date()));
			Writer w = new OutputStreamWriter(new FileOutputStream(outFile), "UTF-8");
			try {
				w.write('﻿');	// SEB's template carries a BOM
				w.write(csvLine(HEADER));
				for (String[] row : rows) {
					w.write(csvLine(row));
				}
			} finally {
				w.close();
			}
		} catch (Exception e) {
			MessageCenter.error(e.getMessage());
			e.printStackTrace();
			return null;
		}

		return outFile;
	}

	/**
	 * One CSV row for the payment rows of one vendor and pay date.
	 */
	String[] createRow(MBankAccount srcAccount, List<LbPaymentRow> lprs) throws Exception {

		LbPaymentRow first = lprs.get(0);
		MBPartner bp = first.getBpartner();
		MBPBankAccount dstBa = first.getDstAccount();
		BankAccountUtil bau = BankAccountUtil.buildFromMBPBankAccount(dstBa);

		BigDecimal amount = Iso20022Helper.calculatePayAmount(lprs);
		if (amount.signum() < 0) {
			throw new Exception("Total amount to pay must not be negative for " + bp.getName());
		}

		// References: the vendor's own numbers go in the message, our
		// document numbers in the private note. A single ISO 11649 (RF)
		// reference is the only thing SEPA's structured field accepts.
		StringBuilder message = new StringBuilder();
		StringBuilder ownNote = new StringBuilder();
		String creditorReference = "";
		for (LbPaymentRow lpr : lprs) {
			String ref = vendorReference(lpr.getInvoice());
			if (ref != null) {
				if (lprs.size() == 1 && ref.toUpperCase().startsWith("RF")) {
					creditorReference = ref;
				} else {
					if (message.length() > 0) message.append(' ');
					message.append(ref);
				}
			}
			if (ownNote.length() > 0) ownNote.append(' ');
			ownNote.append(lpr.getInvoice().getDocumentNo());
		}
		String messageText = message.length() > MAX_MESSAGE_LENGTH
				? message.substring(0, MAX_MESSAGE_LENGTH) : message.toString();

		MLocation loc = getRemitToLocation(bp);

		String[] row = new String[HEADER.length];
		for (int i = 0; i < row.length; i++) row[i] = "";
		row[0] = PAYMENT_TYPE;
		row[1] = nz(BgUtil.removeBlanks(srcAccount.getIBAN()));
		row[2] = srcAccount.getBank().getSwiftCode() != null
				? srcAccount.getBank().getSwiftCode().trim().toUpperCase() : "";
		row[3] = nz(BgUtil.removeBlanks(bau.getIban()));
		row[4] = AMOUNT_FORMAT.format(amount);
		row[5] = new SimpleDateFormat(DATE_FORMAT).format(first.payDate);
		row[6] = SERVICE_LEVEL;
		row[7] = nz(bp.getName());
		if (loc != null) {
			row[8] = loc.getCountry() != null ? nz(loc.getCountry().getCountryCode()) : "";
			row[9] = nz(loc.getAddress1());
			row[11] = nz(loc.getCity());
			row[12] = nz(loc.getPostal());
		}
		row[13] = messageText;
		row[14] = creditorReference;
		row[15] = ownNote.toString();
		row[17] = first.getInvoice().getDocumentNo();	// same as EndToEndId in the PAIN file

		return row;
	}

	/**
	 * The reference the vendor wants: OCR when the invoice carries one,
	 * otherwise the vendor's invoice number.
	 */
	private static String vendorReference(MInvoice invoice) {
		boolean isOcr = "Y".equalsIgnoreCase(invoice.get_ValueAsString("isOCR"));
		String ocr = invoice.get_ValueAsString("OCR");
		String bpDocNo = invoice.get_ValueAsString("BPDocumentNo");
		if (isOcr && ocr != null && ocr.trim().length() > 0) return ocr.trim();
		if (bpDocNo != null && bpDocNo.trim().length() > 0) return bpDocNo.trim();
		return null;
	}

	private static MLocation getRemitToLocation(MBPartner bp) {
		MBPartnerLocation[] locations = bp.getLocations(true);
		if (locations == null || locations.length == 0) return null;
		if (locations.length == 1) return locations[0].getLocation(false);
		for (MBPartnerLocation bloc : locations) {
			if (bloc.isRemitTo()) return bloc.getLocation(false);
		}
		return null;
	}

	private static String nz(String s) {
		return s == null ? "" : s.trim();
	}

	/**
	 * RFC 4180 line: fields quoted when they contain a comma, a quote or a
	 * line break, quotes doubled. Line feed only, like SEB's template.
	 */
	static String csvLine(String[] fields) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < fields.length; i++) {
			if (i > 0) sb.append(',');
			String f = fields[i] == null ? "" : fields[i];
			if (f.indexOf(',') >= 0 || f.indexOf('"') >= 0 || f.indexOf('\n') >= 0 || f.indexOf('\r') >= 0) {
				sb.append('"').append(f.replace("\"", "\"\"")).append('"');
			} else {
				sb.append(f);
			}
		}
		sb.append('\n');
		return sb.toString();
	}

}
