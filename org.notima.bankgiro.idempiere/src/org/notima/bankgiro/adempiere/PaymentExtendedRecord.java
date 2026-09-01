package org.notima.bankgiro.adempiere;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.xml.bind.annotation.XmlTransient;

import org.compiere.model.MBPartner;
import org.compiere.model.MBankAccount;
import org.compiere.model.MInvoice;
import org.compiere.model.MOrder;
import org.compiere.model.MPayment;

/**
 * General payment class for payment information. The programmer can choose which fields to populate.
 * 
 * @author daniel.tamm
 *
 */
public class PaymentExtendedRecord {

	@XmlTransient
	protected MPayment		m_adempierePayment;
	@XmlTransient
	protected MBPartner		m_bPartner;
	@XmlTransient
	protected MInvoice		m_invoice;
	@XmlTransient
	protected MOrder		m_order;
	protected String		bpInvoiceNo;
	@XmlTransient
	protected org.notima.bg.Transaction	m_transaction;
	protected List<PaymentFeeRecord> paymentFees;
	@XmlTransient
	protected MInvoice		feeInvoice;
	protected boolean		m_bPartnerIdentified;
	protected boolean		processed;
	@XmlTransient
	protected MBankAccount	bankAccountPtr;

	private Date trxDate;
	private String orderNo;
	private String invoiceNo;
	private String taxId;
	private String name;
	private String description;
	private String address;
	private String zipCode;
	private String city;
	private String bpCustomerNo;
	private String paymentReference;
	private String otherPaymentReference;
	private double orderSum;
	private String currency;
	private String bankAccount;
	private boolean forceAsCustomerPayment;
	private boolean forceComplete;
	private boolean noMatchExpected;
	private List<String>	messages;
	
	// Map for arbitrary parameters.
	private Map<Object,Object> map;

	@XmlTransient
	public MPayment getAdempierePayment() {
		return m_adempierePayment;
	}
	public void setAdempierePayment(MPayment payment) {
		m_adempierePayment = payment;
	}
	
	public void putValue(Object key, Object value) {
		if (map==null)
			map = new TreeMap<Object,Object>();
		
		map.put(key,  value);
	}

	public Object getValue(Object key) {
		if (map==null) return null;
		return map.get(key);
	}
	
	@XmlTransient
	public MBPartner getBPartner() {
		return m_bPartner;
	}
	
	public void setBPartner(MBPartner partner) {
		m_bPartner = partner;
	}
	
	@XmlTransient
	public MInvoice getInvoice() {
		return m_invoice;
	}
	public void setInvoice(MInvoice m_invoice) {
		this.m_invoice = m_invoice;
	}
	
	public boolean isBPartnerIdentified() {
		return m_bPartnerIdentified;
	}
	public void setBPartnerIdentified(boolean partnerIdentified) {
		m_bPartnerIdentified = partnerIdentified;
	}
	
	@XmlTransient
	public void setOrder(MOrder order) {
		m_order = order;
	}
	
	public MOrder getOrder() {
		return(m_order);
	}
	
	@XmlTransient
	public org.notima.bg.Transaction getTransaction() {
		return m_transaction;
	}
	
	public void setTransaction(org.notima.bg.Transaction transaction) {
		this.m_transaction = transaction;
	}
	public String getBpInvoiceNo() {
		return bpInvoiceNo;
	}
	public void setBpInvoiceNo(String bpInvoiceNo) {
		this.bpInvoiceNo = bpInvoiceNo;
	}
	
	public Date getTrxDate() {
		return trxDate;
	}
	public void setTrxDate(Date trxDate) {
		this.trxDate = trxDate;
	}
	
	public String getOrderNo() {
		return orderNo;
	}
	
	public void setOrderNo(String orderNo) {
		this.orderNo = orderNo;
	}
	
	public String getInvoiceNo() {
		return invoiceNo;
	}
	
	public void setInvoiceNo(String invoiceNo) {
		this.invoiceNo = invoiceNo;
	}
	
	public double getOrderSum() {
		return orderSum;
	}
	
	public void setOrderSum(double orderSum) {
		this.orderSum = orderSum;
	}
	
	public String getCurrency() {
		return currency;
	}
	
	public void setCurrency(String currency) {
		this.currency = currency;
	}
	
	/**
	 * Payment fees are used to specify a fee that deducts the value of the payment
	 * Payment amount + payment fees equals the amount of the amount the payer paid.
	 * 
	 * @return
	 */
	public List<PaymentFeeRecord> getPaymentFees() {
		return paymentFees;
	}
	public void setPaymentFees(List<PaymentFeeRecord> paymentFees) {
		this.paymentFees = paymentFees;
	}

	/**
	 * 
	 * @return A comma separated list of fee payment document no.
	 */
	public String getPaymentFeeDocumentNo() {
		if (paymentFees==null) return null;
		if (paymentFees.size()>0) {
			StringBuffer buf = new StringBuffer();
			for(PaymentFeeRecord r : paymentFees) {
				if (r.getFeePayment()!=null) {
					if (buf.length()>0)
						buf.append(", ");
					buf.append(r.getFeePayment().getDocumentNo());
				}
			}
			return buf.toString();
		} else {
			return null;
		}
	}
	
	/**
	 * 
	 * @return	Total fee amount
	 */
	public double getFeeAmount() {
		if (paymentFees==null) return 0.0;
		double result = 0.0;
		for (PaymentFeeRecord r : paymentFees) {
			result += r.getFeeTotalIncVat();
		}
		return result;
	}
	
	/**
	 * The fee invoice is normally constructed by the payment factory implementation.
	 * If a fee invoice exists, this is allocated on the payment.  
	 * 
	 * @return
	 */
	@XmlTransient	
	public MInvoice getFeeInvoice() {
		return feeInvoice;
	}
	public void setFeeInvoice(MInvoice feeInvoice) {
		this.feeInvoice = feeInvoice;
	}
	
	// If this flag is set, the payment / other are completed even if payment is not matched to an invoice
	// Business partner must be matched however.
	public boolean isForceComplete() {
		return forceComplete;
	}
	
	public void setForceComplete(boolean flag) {
		forceComplete = true;
	}

	// Set when the record can never match by design (aggregator payouts,
	// bank charges, references that are not ours). These are reported as not
	// processed but excluded from the dry-run safety-valve ratio.
	public boolean isNoMatchExpected() {
		return noMatchExpected;
	}

	public void setNoMatchExpected(boolean flag) {
		noMatchExpected = flag;
	}
	
	public String getTaxId() {
		return taxId;
	}
	
	public void setTaxId(String taxId) {
		this.taxId = taxId;
	}
	
	public String getName() {
		return name;
	}
	
	public void setName(String name) {
		this.name = name;
	}
	
	public String getDescription() {
		return description;
	}
	
	public void setDescription(String description) {
		this.description = description;
	}
	
	public String getAddress() {
		return address;
	}
	
	public void setAddress(String address) {
		this.address = address;
	}
	
	public String getZipCode() {
		return zipCode;
	}
	
	public void setZipCode(String zipCode) {
		this.zipCode = zipCode;
	}
	
	public String getCity() {
		return city;
	}
	
	public void setCity(String city) {
		this.city = city;
	}
	
	public String getPaymentReference() {
		return paymentReference;
	}
	public void setPaymentReference(String paymentReference) {
		this.paymentReference = paymentReference;
	}
	
	/**
	 * Other payment reference is used to reference another payment. 
	 * For example: If this payment is a credit of a previous payment, the reference
	 * of the previous payment would be in this field.
	 * 
	 * @return
	 */
	public String getOtherPaymentReference() {
		return otherPaymentReference;
	}
	public void setOtherPaymentReference(String otherPaymentReference) {
		this.otherPaymentReference = otherPaymentReference;
	}
	public String getBpCustomerNo() {
		return bpCustomerNo;
	}
	public void setBpCustomerNo(String bpCustomerNo) {
		this.bpCustomerNo = bpCustomerNo;
	}
	public List<String> getMessages() {
		return messages;
	}
	
	public void setMessages(List<String> messages) {
		this.messages = messages;
	}
	
	public void addMessage(String msg) {
		if (messages==null)
			messages = new ArrayList<String>();
		messages.add(msg);
	}
	
	@XmlTransient
	public String getMessagesAsString() {
		StringBuffer buf = new StringBuffer();
		if (messages!=null) {
			for (String s : messages) {
				if (buf.length()>0)
					buf.append("\r\n");
				buf.append(s);
			}
		}
		return buf.toString();
	}

	public boolean isProcessed() {
		return processed;
	}
	public void setProcessed(boolean processed) {
		this.processed = processed;
	}

	/**
	 * String representation of the src/dst bankaccount
	 * @return
	 */
	public String getBankAccount() {
		return bankAccount;
	}
	public void setBankAccount(String bankAccount) {
		this.bankAccount = bankAccount;
	}
	/**
	 * 
	 * @return 	Name and address in one string.
	 */
	@XmlTransient	
	public String getNameAndAddress() {
		StringBuffer buf = new StringBuffer();
		
		if (name!=null && name.trim().length()>0) {
			buf.append(name);
		}
		if (address!=null && address.trim().length()>0) {
			if (buf.length()>0)
				buf.append(", ");
			buf.append(address);
		}
		if (zipCode!=null && zipCode.trim().length()>0) {
			if (buf.length()>0)
				buf.append(", ");
			buf.append(zipCode);
		}
		if (city!=null && city.trim().length()>0) {
			if (buf.length()>0)
				buf.append(", ");
			buf.append(city);
		}
		
		return buf.toString();
		
	}
	
	public MBankAccount getBankAccountPtr() {
		return bankAccountPtr;
	}
	public void setBankAccountPtr(MBankAccount bankAccountPtr) {
		this.bankAccountPtr = bankAccountPtr;
	}
	
	/**
	 * If true, this payment becomes a customer payment regardless of signum.
	 * 
	 * @return
	 */
	public boolean isForceAsCustomerPayment() {
		return forceAsCustomerPayment;
	}
	public void setForceAsCustomerPayment(boolean forceAsCustomerPayment) {
		this.forceAsCustomerPayment = forceAsCustomerPayment;
	}
	
	
	
}
