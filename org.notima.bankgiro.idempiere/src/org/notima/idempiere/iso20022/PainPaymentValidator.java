package org.notima.idempiere.iso20022;

import org.compiere.model.MBankAccount;
import org.compiere.model.MDocType;
import org.compiere.model.MInvoice;
import org.notima.bankgiro.adempiere.BankAccountUtil;
import org.notima.bankgiro.adempiere.BasicPaymentValidator;
import org.notima.bankgiro.adempiere.LbPaymentRow;

public class PainPaymentValidator extends BasicPaymentValidator {

	@Override
	public String validatePayment(MBankAccount srcAccount, LbPaymentRow pmt) {
		String result = super.validatePayment(srcAccount, pmt);
		if (result!=null)
			return result;
		
		BankAccountUtil bau = BankAccountUtil.buildFromMBPBankAccount(pmt.getDstAccount());
		
		if (bau.getAccountType().equals(BankAccountUtil.BankAccountType.NONE)) {
			return "Recipient bank account must be defined.";
		}
		
		if (bau.getAccountType().equals(BankAccountUtil.BankAccountType.CHECK)) {
			return "Check not yet supported";
		}
		
        // Check for credit notes (not yet supported)
		/*
        MInvoice invoice = pmt.getInvoice();
        // Get document type
        MDocType dt = (MDocType)invoice.getC_DocType();
        if ( MDocType.DOCBASETYPE_APCreditMemo.equals(dt.getDocBaseType()) && invoice.getGrandTotal().signum()>0) {
        	return "Vendor credit memos not yet supported";
        }
        if ( MDocType.DOCBASETYPE_APInvoice.equals(dt.getDocBaseType()) && invoice.getGrandTotal().signum()<0) {
        	return "Vendor credit memos not yet supported";
        } */
        
		return null;
	}

	
	
}
