package org.notima.idempiere.iso20022;

import org.compiere.model.MBankAccount;
import org.notima.bankgiro.adempiere.BankAccountUtil;
import org.notima.bankgiro.adempiere.BasicPaymentValidator;
import org.notima.bankgiro.adempiere.LbPaymentRow;

/**
 * A SEPA credit transfer needs an IBAN with a BIC on the receiving side and
 * is always in EUR.
 */
public class SebSepaPaymentValidator extends BasicPaymentValidator {

	@Override
	public String validatePayment(MBankAccount srcAccount, LbPaymentRow pmt) {
		String result = super.validatePayment(srcAccount, pmt);
		if (result != null)
			return result;

		BankAccountUtil bau = BankAccountUtil.buildFromMBPBankAccount(pmt.getDstAccount());

		if (!bau.getAccountType().equals(BankAccountUtil.BankAccountType.IBAN)) {
			return "SEPA payments need an IBAN and a BIC on the recipient's bank account"
					+ " (found " + bau.getAccountType() + ")";
		}

		if (pmt.currency == null || !"EUR".equalsIgnoreCase(pmt.currency)) {
			return "SEPA payments are in EUR, the invoice is in " + pmt.currency;
		}

		return null;
	}

}
