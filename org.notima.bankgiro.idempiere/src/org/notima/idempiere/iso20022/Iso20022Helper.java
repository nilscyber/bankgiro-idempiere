package org.notima.idempiere.iso20022;

import java.math.BigDecimal;
import java.util.List;

import org.compiere.model.MInvoice;
import org.notima.bankgiro.adempiere.BankAccountUtil;
import org.notima.bankgiro.adempiere.LbPaymentRow;
import org.notima.idempiere.iso20022.entity.pain.ActiveOrHistoricCurrencyAndAmount;
import org.notima.idempiere.iso20022.entity.pain.BranchAndFinancialInstitutionIdentification4;
import org.notima.idempiere.iso20022.entity.pain.ClearingSystemIdentification2Choice;
import org.notima.idempiere.iso20022.entity.pain.ClearingSystemMemberIdentification2;
import org.notima.idempiere.iso20022.entity.pain.CreditorReferenceInformation2;
import org.notima.idempiere.iso20022.entity.pain.CreditorReferenceType1Choice;
import org.notima.idempiere.iso20022.entity.pain.CreditorReferenceType2;
import org.notima.idempiere.iso20022.entity.pain.DocumentType3Code;
import org.notima.idempiere.iso20022.entity.pain.DocumentType5Code;
import org.notima.idempiere.iso20022.entity.pain.FinancialInstitutionIdentification7;
import org.notima.idempiere.iso20022.entity.pain.ReferredDocumentInformation3;
import org.notima.idempiere.iso20022.entity.pain.ReferredDocumentType1Choice;
import org.notima.idempiere.iso20022.entity.pain.ReferredDocumentType2;
import org.notima.idempiere.iso20022.entity.pain.RemittanceAmount1;
import org.notima.idempiere.iso20022.entity.pain.RemittanceInformation5;
import org.notima.idempiere.iso20022.entity.pain.StructuredRemittanceInformation7;

public class Iso20022Helper {

	
	public static BranchAndFinancialInstitutionIdentification4 getCreditorAgent(BankAccountUtil bau) {
		
		BranchAndFinancialInstitutionIdentification4 bafiit = new BranchAndFinancialInstitutionIdentification4();
		FinancialInstitutionIdentification7 fii = new FinancialInstitutionIdentification7();

		ClearingSystemMemberIdentification2 clrSys = getClearingSystemFor(bau);
		if (clrSys!=null) {
			fii.setClrSysMmbId(clrSys);
		}
		if (bau.getAccountType().equals(BankAccountUtil.BankAccountType.IBAN)) {
			fii.setBIC(bau.getSwift().toUpperCase());
		}
		bafiit.setFinInstnId(fii);
		
		return bafiit;
		
	}

	public static ClearingSystemMemberIdentification2 getClearingSystemFor(BankAccountUtil bau) {

		switch (bau.getAccountType()) {
			case BANKGIRO: 
				return getClearingSystemBankgirot();
			case PLUSGIRO:
				return getClearingSystemPlusgirot();
			case DOMESTIC_BANKACCT:
				return getClearingSystemForDomesticBank(bau.getClearing());
			default:
				break;
		}

		return null;
		
	}
	
	
	public static ClearingSystemMemberIdentification2 getClearingSystemForDomesticBank(String clearing) {
		return getClearingSystemDomesticBankTransfer(clearing);
	}
	
	
	
	private static ClearingSystemMemberIdentification2 getClearingSystemBankgirot() {
		return getClearingSystemDomesticBankTransfer("9900");
	}
	
	private static ClearingSystemMemberIdentification2 getClearingSystemPlusgirot() {
		return getClearingSystemDomesticBankTransfer("9500");
	}

	public static ClearingSystemMemberIdentification2 getClearingSystemDomesticBankTransfer(String clearing) {
		return getClearingSystem("SESBA", clearing);
	}
	
	private static ClearingSystemMemberIdentification2 getClearingSystem(String choice, String mmbId) {
		ClearingSystemMemberIdentification2 clrSys = new ClearingSystemMemberIdentification2(); 
		ClearingSystemIdentification2Choice clrChoice = new ClearingSystemIdentification2Choice();
		clrChoice.setCd(choice);
		clrSys.setClrSysId(clrChoice);
		clrSys.setMmbId(mmbId);
		return clrSys;
	}
	
	/**
	 * Creates a remittance information list. All payment rows should be the same business partner.
	 * 
	 * @param lprs
	 * @return
	 */
	public static RemittanceInformation5 createRemittanceInformation(List<LbPaymentRow> lprs) throws Exception {

		checkPaymentRowsBelongToSameBp(lprs);
		
        RemittanceInformation5 rmt = new RemittanceInformation5();
        
        StructuredRemittanceInformation7 strd;
        
        for (LbPaymentRow lpr : lprs) {
        	strd = createRemittanceInformation(lpr);
        	rmt.getStrd().add(strd);
        }
		
        return rmt;
	}

	public static BigDecimal calculatePayAmount(List<LbPaymentRow> lprs) {
		
		double totalAmount = 0.0;
		
		for (LbPaymentRow lpr : lprs) {
			
			totalAmount += getAmountToPay(lpr).doubleValue();
			
		}
		
		return BigDecimal.valueOf(totalAmount).setScale(2, BigDecimal.ROUND_HALF_EVEN);
		
	}

	/**
	 * Returns amount to pay on a payment row. Adjusts whether it's a customer credit note 
	 * or a vendor invoice.
	 * 
	 * @param lpr
	 * @return
	 */
	public static BigDecimal getAmountToPay(LbPaymentRow lpr) {

		boolean customerInvoice;
		double totalAmount = 0.0;
		
		customerInvoice = lpr.getInvoice()!=null && lpr.getInvoice().isSOTrx();
		if (lpr.arCreditMemo) {
			if (!customerInvoice)
				totalAmount -= lpr.payAmount;
			else
				totalAmount += lpr.payAmount;
		} else {
			if (!customerInvoice)
				totalAmount += lpr.payAmount;
			else
				totalAmount -= lpr.payAmount;
		}
		
		return BigDecimal.valueOf(totalAmount).setScale(2, BigDecimal.ROUND_HALF_EVEN);
		
	}
	
	private static void checkPaymentRowsBelongToSameBp(List<LbPaymentRow> lprs) throws Exception {
        int bpartnerId = 0;
        String currency = null;
        for (LbPaymentRow lpr : lprs) {
	    	if (bpartnerId == 0) {
	    		bpartnerId = lpr.bpartner_ID;
	    	}
	    	if (currency==null) {
	    		currency = lpr.currency;
	    	}
	    	if (bpartnerId != lpr.bpartner_ID) {
	    		throw new Exception("Can't mix invoices from different business partners");
	    	}
	    	if (currency!=null) {
	    		if (!currency.equalsIgnoreCase(lpr.currency)) {
	    			throw new Exception("Can't mix invoices with different currency");
	    		}
	    	}
        }
	}
	
	/**
	 * Tries to extract the most likely reference.
	 * 
	 * TODO: Make it more robust and handle more cases
	 * 
	 * @param stri
	 * @return
	 */
	public static String getMostLikelyReference(iso.std.iso._20022.tech.xsd.camt_054_001.StructuredRemittanceInformation7 stri) {
		
		if (stri==null) return null;
		
		iso.std.iso._20022.tech.xsd.camt_054_001.CreditorReferenceInformation2 cri = stri.getCdtrRefInf();
		
		if (cri!=null) {
			if (cri.getRef()!=null) 
				return cri.getRef();
		} 

		List<iso.std.iso._20022.tech.xsd.camt_054_001.ReferredDocumentInformation3> rlist = stri.getRfrdDocInf();
		if (rlist!=null && rlist.size()>0) {
			iso.std.iso._20022.tech.xsd.camt_054_001.ReferredDocumentInformation3 rdi = rlist.get(0);
			if (rdi.getNb()!=null)
				return rdi.getNb();
		}
		
		return "";
	}
	
	/**
	 * Creates a structure remittance information for a single invoice
	 * 
	 * @param lpr
	 * @return
	 */
	private static StructuredRemittanceInformation7 createRemittanceInformation(LbPaymentRow lpr) {
		
		MInvoice invoice = lpr.getInvoice();		
        boolean isOCR = invoice.get_Value("isOCR")!=null && "true".equalsIgnoreCase(invoice.get_ValueAsString("isOCR"));
        String OCR = (String)invoice.get_Value("OCR"); 				    // OCR reference
        String BPInvoiceNo = (String)invoice.get_Value("BPDocumentNo");		// Invoice number if not OCR

    	StructuredRemittanceInformation7 strd = new StructuredRemittanceInformation7();
    	
    	strd.getRfrdDocInf().add(getInvoiceTypeAndReference(BPInvoiceNo, getAmountToPay(lpr).signum()<0));
    	
    	strd.setRfrdDocAmt(getRemittanceAmount(lpr));
        
        if (isOCR) {
        	CreditorReferenceInformation2 cri = new CreditorReferenceInformation2();
        	cri.setRef(OCR);
        	strd.setCdtrRefInf(cri);
        	
        	CreditorReferenceType2 tp = new CreditorReferenceType2();
        	cri.setTp(tp);
        	CreditorReferenceType1Choice cdor = new CreditorReferenceType1Choice();
        	tp.setCdOrPrtry(cdor);
        	cdor.setCd(DocumentType3Code.SCOR);
        } 

        return strd;
	}
	
	private static RemittanceAmount1 getRemittanceAmount(LbPaymentRow lpr) {
		
		RemittanceAmount1 rmtAmount = new RemittanceAmount1();
		ActiveOrHistoricCurrencyAndAmount amount = new ActiveOrHistoricCurrencyAndAmount();
		amount.setCcy(lpr.currency);
		BigDecimal amountToPay = Iso20022Helper.getAmountToPay(lpr);
		amount.setValue(amountToPay.abs());
		if (amountToPay.signum()<0) {
			rmtAmount.setCdtNoteAmt(amount);
		} else {
			rmtAmount.setRmtdAmt(amount);
		}
		return rmtAmount;
		
	}
	
	private static ReferredDocumentInformation3 getInvoiceTypeAndReference(String invoiceNumber, boolean isCreditNote) {

    	ReferredDocumentInformation3 rfrdDocInf = new ReferredDocumentInformation3();
    	ReferredDocumentType2 tp2 = new ReferredDocumentType2();
    	ReferredDocumentType1Choice type1Choice = new ReferredDocumentType1Choice();
    	
    	type1Choice.setCd(isCreditNote ? DocumentType5Code.CREN : DocumentType5Code.CINV);
    	tp2.setCdOrPrtry(type1Choice);
    	rfrdDocInf.setTp(tp2);
    	rfrdDocInf.setNb(invoiceNumber);
		
    	return rfrdDocInf;
		
	}
	
	
}
