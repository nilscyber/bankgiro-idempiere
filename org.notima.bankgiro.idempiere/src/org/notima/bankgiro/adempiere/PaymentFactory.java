package org.notima.bankgiro.adempiere;

import java.io.File;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import java.util.Properties;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.Vector;

import javax.swing.JOptionPane;

import org.adempiere.exceptions.AdempiereException;
import org.compiere.model.MAllocationHdr;
import org.compiere.model.MAllocationLine;
import org.compiere.model.MBPartner;
import org.compiere.model.MBankAccount;
import org.compiere.model.MCurrency;
import org.compiere.model.MDocType;
import org.compiere.model.MInvoice;
import org.compiere.model.MInvoiceLine;
import org.compiere.model.MOrder;
import org.compiere.model.MPayment;
import org.compiere.model.MPriceList;
import org.compiere.model.Query;
import org.compiere.process.DocAction;
import org.compiere.util.CLogger;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Ini;
import org.compiere.util.Trx;
import org.notima.bankgiro.adempiere.model.MLBSettings;
import org.notima.bg.BgUtil;


/**
 * <p>Use this class to implement different payment creation processes.
 * The class allows a vector of ReceivablesPayment to be returned which
 * in turn can be used to create a report of the payments.</p>
 * 
 * <p>NOTE! The payment factory deals with inbound payments, meaning payments
 * that is created in the system after actually have happened for real</p>
 * 
 * <p>For outbound payments (ie sending payment requests) see org.notima.bankgiro.adempiere.PaymentFileFactory</p>
 * 
 * @author Daniel Tamm
 * @see	   PaymentFileFactory
 *
 */
public abstract class PaymentFactory  implements Runnable {

	/**
	 * Maximum number of uncompleted payments allowed for dry run not to become enabled.
	 */
	private static final int MAX_UNCOMPLETED_PAYMENTS = 30;
	
	protected Properties	m_ctx;
	
	protected java.util.Date trxDate;
	
	// Always supply client ID
	protected int			m_clientId;
	// .. and Org ID
	protected int			m_orgId;
	// The bankaccount where the payments are deposited / deducted
    protected MBankAccount    m_ourBa;
    // Payments that are left unprocessed.
	protected Vector<PaymentExtendedRecord>	m_notProcessedPayments = new Vector<PaymentExtendedRecord>();
	// A collection of all payments (including un processed)
	protected List<PaymentExtendedRecord> m_payments;
	// If set to true, nothing is committed.
	protected boolean		m_dryRun = false;
	// The id of the unknown BPartner used for unidentified payments.
	protected int			m_unknownBPartnerId;
	protected MBPartner		m_unknownBPartner;
	// Logger
	protected CLogger		m_log = CLogger.getCLogger(this.getClass());
	// LB Settings
	protected SortedMap<String, MLBSettings>	m_lbSettings;

	protected String		m_currency;
	
	protected double		m_amountThreshold;
	protected boolean		m_autoComplete;
	
	protected Properties	m_properties = new Properties();
	
	/**
	 * The name of the file that was read when creating the payments (excluding path).
	 * If the payments wasn't created from a file, this attribute should be something that uniquely 
	 * defines the report data.
	 * 
	 * The file name is used to check that the same report isn't read more than once.
	 */
	protected String		fileName;
	
	/**
	 * If a file was source for the payments, this is the pointer to the file.
	 */
	protected File			file;
	
	/**
	 * One and only constructor. All parameters must be supplied.
	 * 
	 * @param lbSettings			Plugin-module specific settings. Available settings are
	 * 								MLBSettings.BG_DRYRUN_FLAG
	 * 								MLBSettings.AMT_THRESHOLD
	 * 								MLBSettings.PMT_AUTOCOMPLETE
	 * 
	 * @param ctx					Adempiere-context			
	 * @param ourBa					The bank account where the payments are created.
	 * 
	 */
	public PaymentFactory(SortedMap<String,MLBSettings> lbSettings, Properties ctx, MBankAccount ourBa) {

		init(lbSettings, ctx);
		setBankAccount(ourBa);
        
	}

	/**
	 * Alternate constructor if bank account is changed
	 * 
	 * @param lbSettings
	 * @param ctx
	 */
	public PaymentFactory(SortedMap<String,MLBSettings> lbSettings, Properties ctx) {
		
		init(lbSettings, ctx);
		
	}

	public void setBankAccount(MBankAccount ba) {

		m_ourBa = ba;
		m_clientId = ba.getAD_Client_ID();
        m_orgId = ba.getAD_Org_ID();
        m_currency = ba.getC_Currency().getISO_Code();
		
	}
	
	private void init(SortedMap<String, MLBSettings> lbSettings, Properties ctx) {
		
        m_ctx = ctx;
    	// Determine if dry run is enabled
        m_lbSettings = lbSettings!=null ? lbSettings : new TreeMap<String, MLBSettings>();
    	MLBSettings dryRun = m_lbSettings.get(MLBSettings.BG_DRYRUN_FLAG);
    	m_dryRun = (dryRun!=null && "true".equalsIgnoreCase(dryRun.getName()));
    	
		m_amountThreshold = 0;
		MLBSettings amtThresholdSetting = m_lbSettings.get(MLBSettings.AMT_THRESHOLD);
		if (amtThresholdSetting!=null) {
			m_amountThreshold = Double.parseDouble(amtThresholdSetting.getName());
		}
		m_autoComplete = false;
		MLBSettings autoCompleteSetting = m_lbSettings.get(MLBSettings.PMT_AUTO_COMPLETE);
		if (autoCompleteSetting!=null) {
			m_autoComplete = !"false".equalsIgnoreCase(autoCompleteSetting.getName());
		}
		
    	MLBSettings unknownPayer = m_lbSettings.get(MLBSettings.BG_UNKNOWN_PAYER_BPID);
    	if (unknownPayer!=null) {
    		m_unknownBPartnerId = new Integer(unknownPayer.getName()).intValue();
    	} else {
    		m_log.warning("Unknown business partner not set for payment plugin " + this.getClass().getName());
    	}
		
		
	}

	/**
	 * Properties are sent to the getSourcePayments method.
	 * 
	 * @param key
	 * @param value
	 */
	public void setProperty(String key, String value) {
		m_properties.setProperty(key, value);
	}

	/**
	 * Use this to put arbitrary objects to the property variable.
	 * 
	 * @param key
	 * @param value
	 */
	public void putProperty(String key, Object value) {
		m_properties.put(key, value);
	}
	
	public java.util.Date getTrxDate() {
		return trxDate;
	}
	
	public void setTrxDate(java.util.Date d) {
		trxDate = d;
	}

	
	/**
	 * The name of the file that was read when creating the payments.
	 * If the payments wasn't created from a file, this attribute should be something that uniquely 
	 * defines the report data.
	 * 
	 * The file name is used to check that the same report isn't read more than once.
	 */
	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	/**
	 * If a file was source for the payments, this is the pointer to the file.
	 */
	public File getFile() {
		return file;
	}

	/**
	 * Sets the file to read. Automatically updates the filename associated 
	 * with the payment factory.
	 * 
	 * The file name can be set without the file but not the other way around.
	 * 
	 * @param file
	 */
	public void setFile(File file) {
		this.file = file;
		fileName = file.getName();
	}

	/**
	 * Method that gets source payments. Arguments needed by the child class are normally passed in 
	 * the props object.
	 * 
	 * @param props
	 * @return	A list of PaymentExtendedRecords.
	 * @throws Exception
	 */
	protected abstract List<PaymentExtendedRecord> getSourcePayments(Properties props) throws Exception;	

	/**
	 * Method that allows additional information to be supplied to (created) the payment before 
	 * it is saved.
	 * 
	 * Override to customize.
	 * 
	 * Called from createPayment 
	 * 
	 * @param pmt
	 * @return The payment
	 * @throws Exception
	 */
	protected PaymentExtendedRecord beforeSaveAndProcess(PaymentExtendedRecord pmt) throws Exception {
		return pmt;
	}
	
	/**
	 * Method that returns the total amount of the source payments. For best control this should be the
	 * amount reported by the payment provider (ie the amount that was actually received to the account/bank).
	 * @return
	 */
	//protected abstract double getTotalAmountSourcePayments();
	
	/**
	 * Gets/creates a fee invoice.
	 * 
	 * If the payment fees list in the payment extended record is non empty an invoice is created from the list.
	 * If a payment fee invoice already exists on the PaymentExtendedRecord the invoice is returned.
	 * 
	 * An existing ADempiere payment must exist in the PaymentExtendedRecord
	 * 
	 * @param props
	 * @param rec
	 * @return
	 * @throws Exception
	 */
	protected MInvoice getFeeInvoice(Properties ctx, Properties props, PaymentExtendedRecord rec, String trxName) throws Exception {
		
		if (rec.getFeeInvoice()!=null) return(rec.getFeeInvoice());
		
		MInvoice feeInvoice = null;
		// Check that there are records that generates a fee invoice
		boolean hasInvoiceFees = false;
		if (rec.getPaymentFees()!=null && rec.getPaymentFees().size()>0) {
			for (PaymentFeeRecord r : rec.getPaymentFees()) {
				if (!r.isFeeAsPayment()) {
					hasInvoiceFees = true;
					break;
				}
			}
		}
		if (!hasInvoiceFees)
			return null;
		
		feeInvoice = new MInvoice(ctx, 0, trxName);
		MPayment pmt = rec.getAdempierePayment();
		MOrder order = rec.getOrder();
		feeInvoice.setC_DocType_ID(MDocType.getDocType(MDocType.DOCBASETYPE_APInvoice));
		feeInvoice.setBPartner((MBPartner)pmt.getC_BPartner());
		// Get price list (purchase)
		MPriceList pl = (MPriceList)feeInvoice.getM_PriceList();
		if (pl==null || pl.isSOPriceList()) {
			pl = new Query(ctx, MPriceList.Table_Name, "IsActive='Y' AND IsDefault='Y' and IsSoPriceList='N' and C_Currency_ID=?", trxName)
							.setClient_ID().setParameters(new Object[]{pmt.getC_Currency_ID()})
							.first();
			if (pl!=null)
				feeInvoice.setM_PriceList_ID(pl.get_ID());
		}
		if (order!=null) feeInvoice.setOrder(order);
		feeInvoice.setIsSOTrx(false);
		feeInvoice.setDateInvoiced(pmt.getDateAcct());
		feeInvoice.setDateAcct(pmt.getDateAcct());
		feeInvoice.saveEx(trxName);
		feeInvoice.set_ValueOfColumn("bpdocumentno", rec.getBpInvoiceNo());
		// Create lines
		MInvoiceLine line;
		for (PaymentFeeRecord r : rec.getPaymentFees()) {
			line = new MInvoiceLine(ctx, 0, trxName);
			line.setInvoice(feeInvoice);
			line.setC_Invoice_ID(feeInvoice.get_ID());
			if (r.getProduct()!=null)
				line.setProduct(r.getProduct());
			else if (r.getCharge()!=null) {
				line.setC_Charge_ID(r.getCharge().get_ID());
			}
			// Set tax
			line.setTax();
			line.setQty(new BigDecimal(Double.toString(r.getQty())));
			line.setPrice(new BigDecimal(Double.toString(r.getPrice())));
			if (r.isUseVatAmount()) {
				line.setTaxAmt(new BigDecimal(Double.toString(r.getVatAmount())));
			}
			line.saveEx(trxName);
		}
		// Prepare
		boolean feeInvoiceOk = feeInvoice.processIt(DocAction.ACTION_Prepare);
		if (feeInvoiceOk) {
			// Save again
			feeInvoice.saveEx(trxName);
		}
		rec.setFeeInvoice(feeInvoice);
		
		return feeInvoice;
	}
	
	/**
	 * Creates payment fees from current payment record. The payments are stored in the PaymentFeeRecord.
	 * 
	 * @param ctx
	 * @param currentPayment
	 * @param trxName
	 */
	protected void createFeePayments(Properties ctx, PaymentExtendedRecord currentPayment, String trxName) throws Exception {
		
		MPayment feePmt, pmt;
		int chargeId;
		
		if (currentPayment.getPaymentFees()==null) return;
		
		for (PaymentFeeRecord r : currentPayment.getPaymentFees()) {
			if (r.isFeeAsPayment()) {
				// Check that the charge ID is set
				chargeId = r.getChargeId();
				if (chargeId==0) throw new AdempiereException("No charge on payment fee as required");
				pmt = currentPayment.getAdempierePayment();
				feePmt = new MPayment(ctx, 0, trxName);
				feePmt.setC_BankAccount_ID(pmt.getC_BankAccount_ID());
				feePmt.setC_BPartner_ID(pmt.getC_BPartner_ID());
				feePmt.setDateAcct(pmt.getDateAcct());
				feePmt.setDateTrx(pmt.getDateAcct());
				feePmt.setC_Charge_ID(chargeId);
				feePmt.setAmount(pmt.getC_Currency_ID(), BigDecimal.valueOf(r.getQty()*r.getPrice()));
				// Set document type
				feePmt.setIsReceipt(false);
				if (currentPayment.getPaymentReference()!=null) {
					feePmt.setDescription("Fee for payment " + currentPayment.getPaymentReference());
					feePmt.set_ValueOfColumn(MPayment.COLUMNNAME_R_PnRef_DC, currentPayment.getPaymentReference());
				}
				feePmt.saveEx();
				
				r.setFeePayment(feePmt);
			}
		}
		
	}
	
	/**
	 * Enriches a PaymentExtendedRecord with Adempiere information such as order, invoice and bpartner.
	 * 
	 * Creates an MPayment from the currentPayment. A payment is always created regardless of if it is 
	 * matched or not. If the payment is already created, the payment is checked for consistency. 
	 * 
	 * If the payment is matched can be checked by seeing if the payment has a bPartnerId set.
	 * 
	 * If the payment (only receivables) contains fees (@see PaymentFeeRecord) a vendor invoice of the 
	 * fees is created and matched.
	 * 
	 * If an invoice is supplied the invoice must have an open balance to be linked to the payment.
	 * The reason for this is that a payment connected to an invoice can't complete if the invoice
	 * is already paid.
	 * 
	 * @param ctx
	 * @param currentPayment
	 * @param trxName
	 * @param ourBankAccountID
	 * @param currencyId
	 * @return 	The created payment is returned in the adempierePaymentField of the returned PaymentExtendedRecord
	 * @throws Exception
	 */
	/**
	 * Describes the payment being saved, for error messages - a failed file
	 * run should point out the exact record (payer, amount, order, BP).
	 */
	private String describeFailedPayment(PaymentExtendedRecord rec, MPayment payment) {
		StringBuilder sb = new StringBuilder("Could not save payment");
		if (rec.getName()!=null && rec.getName().trim().length()>0)
			sb.append(" from \"" + rec.getName() + "\"");
		sb.append(", amount " + rec.getOrderSum() + (rec.getCurrency()!=null ? " " + rec.getCurrency() : ""));
		if (rec.getOrderNo()!=null && rec.getOrderNo().trim().length()>0)
			sb.append(", order no " + rec.getOrderNo());
		if (rec.getInvoiceNo()!=null && rec.getInvoiceNo().trim().length()>0)
			sb.append(", invoice no " + rec.getInvoiceNo());
		if (rec.getBpInvoiceNo()!=null && rec.getBpInvoiceNo().trim().length()>0)
			sb.append(", their ref " + rec.getBpInvoiceNo());
		if (rec.getBPartner()!=null)
			sb.append(", BP " + rec.getBPartner().getValue() + " \"" + rec.getBPartner().getName() + "\"");
		if (payment!=null && payment.getC_Order_ID()>0)
			sb.append(", payment C_Order_ID " + payment.getC_Order_ID());
		return sb.toString();
	}

    protected PaymentExtendedRecord createPayment(Properties ctx, PaymentExtendedRecord currentPayment, String trxName, int ourBankAccountID, int currencyId) throws Exception {
		MInvoice	invoice = null;
		MOrder		order = null;
		MBPartner	bpartner = null;
		MPayment	payment = null;
		
		// Copy existing entities if already set
		if (currentPayment.m_bPartner!=null)
			bpartner = currentPayment.m_bPartner;
		
		if (currentPayment.m_order!=null)
			order = currentPayment.m_order;
		
		if (currentPayment.m_invoice!=null) 
			invoice = currentPayment.m_invoice;
		
		if (currentPayment.m_adempierePayment!=null) {
			payment = currentPayment.m_adempierePayment;
		}
		
		// Check payment reference
		String paymentReference = currentPayment.getPaymentReference();
		if (paymentReference!=null && paymentReference.trim()!=null) {
			payment = new Query(ctx, MPayment.Table_Name, "R_PnRef=? AND C_BankAccount_ID=? AND NOT DocStatus IN ('VO')", trxName)
				.setParameters(new Object[]{paymentReference, ourBankAccountID})
				.first();
		}
		
		// Check if there's a reference to another payment
		MPayment otherPayment = null;
		String otherPaymentReference = currentPayment.getOtherPaymentReference();
		if (otherPaymentReference!=null && otherPaymentReference.trim()!=null) {
			otherPayment = new Query(ctx, MPayment.Table_Name, "R_PnRef=? AND C_BankAccount_ID=? AND NOT DocStatus IN ('VO')", trxName)
				.setParameters(new Object[]{otherPaymentReference, ourBankAccountID})
				.first();
		}
		
        // Try to find the invoice paid using our invoice no
        // Get document no
        String documentNo = currentPayment.getInvoiceNo();
    	if (documentNo!=null && invoice==null) 
    		invoice = lookupInvoice(ctx, documentNo);
    	
    	if (invoice==null) {
    		// Try looking up using order
    		if (order==null)
    			order = lookupOrder(ctx, currentPayment.getOrderNo());
    		
    		if (order!=null) {
    			// Try to find invoice for order
    			invoice = lookupInvoiceUsingOrderIdAndBpDocumentNo(ctx, order.get_ID(), currentPayment.getBpInvoiceNo());
    			
    			if (invoice==null && currentPayment.getOrderSum()>0)
    				invoice = lookupInvoiceUsingOrderIdAndTotalSum(ctx, order.get_ID(), currentPayment.getOrderSum());

    		} else if (currentPayment.getBpInvoiceNo()!=null && (currentPayment.getBpCustomerNo()==null || currentPayment.getBpCustomerNo().trim().length()==0)) {
    			// Try to find invoice using bp document no
    			invoice = lookupInvoiceUsingBpDocumentNo(ctx, currentPayment,true);
    		} else if (currentPayment.getBpInvoiceNo()!=null && currentPayment.getBpCustomerNo()!=null) {
    			// Try to find invoice using both bp customer no and bp document no
    			invoice = lookupInvoiceUsingBpCustomerNoAndBpDocumentNo(ctx, currentPayment,true);
    			// If invoice still is null, try looking up using only bp document no (fails if multiple matches)
    			if (invoice==null) {
    				invoice = lookupInvoiceUsingBpDocumentNo(ctx, currentPayment,true);
    			}
    		}
			if (invoice!=null) {
	        	// Save back invoice no to currentPayment record
	        	if (currentPayment.getInvoiceNo()==null || currentPayment.getInvoiceNo().trim().length()==0)
	        		currentPayment.setInvoiceNo(invoice.getDocumentNo());
			}    		
    	}
    	
    	// Lookup bpartner
    	// Get from payment
    	if (payment!=null)
    		bpartner = (MBPartner)payment.getC_BPartner();
    	// Get from invoice
        if (invoice!=null && bpartner==null) {
        	bpartner = (MBPartner)invoice.getC_BPartner();
        }
        // Get from order
        if (bpartner==null && order!=null) {
        	bpartner = (MBPartner)order.getC_BPartner();
        }
        // Get from referenced payment
        if (bpartner==null && otherPayment!=null) {
        	bpartner = (MBPartner)otherPayment.getC_BPartner();
        }
        // Last resort: a voided order still identifies the payer. lookupOrder
        // excludes voided orders from matching (correctly - no invoice should
        // be matched through them), but a Qliro/Swish settlement can reference
        // an order that was voided before the file arrived, and without the BP
        // the C_Order_ID reference set further down makes MPayment fail with
        // "BP different from BP Order" and abort the whole file.
        if (bpartner==null && order==null && currentPayment.getOrderNo()!=null) {
        	MOrder voidedOrder = new Query(ctx, MOrder.Table_Name, "DocumentNo=? AND AD_Client_ID=?", trxName)
        			.setParameters(new Object[]{currentPayment.getOrderNo(), Env.getAD_Client_ID(ctx)})
        			.first();
        	if (voidedOrder!=null) {
        		bpartner = (MBPartner)voidedOrder.getC_BPartner();
        	}
        }
        
        // If there's a tax id supplied, cross check and/or use tax id to find business partner
        List<MBPartner> checkBPartners = null;
        if (currentPayment.getTaxId()!=null) {
        	checkBPartners = lookupBpUsingTaxId(ctx, currentPayment.getTaxId(), trxName);
        	// One single result, either cross check or use
        	if (checkBPartners.size()==1) {
        		// Compare
        		if (bpartner!=null && checkBPartners.get(0).get_ID()!=bpartner.get_ID()) {
        			
        			// Check the taxid of the bpartner
        			if (BgUtil.toDigitsOnly(bpartner.getTaxID()).equals(BgUtil.toDigitsOnly(currentPayment.getTaxId()))) {
        				
        				// We're ok, we just have duplicate tax ids.
        				currentPayment.addMessage("Warning: Duplicate tax id on " + checkBPartners.get(0).getValue() + " : " + checkBPartners.get(0).getName());
        				
        			} else {
        			
	        			currentPayment.setBPartnerIdentified(false);
	        			currentPayment.addMessage("Conflicting business partners. Order/Invoice says " 
	        										+ bpartner.getValue() + " : " + bpartner.getName() + " but tax id says " + 
	        										checkBPartners.get(0).getValue() + " : " + checkBPartners.get(0).getName());
	        			currentPayment.addMessage("Tax id: " + currentPayment.getTaxId());
	        			
	        			bpartner=null; // Set BPartner to null since it can't be identified, we get different bpartners.
	        			
        			}
        			
        		} else if (bpartner==null) {
        			bpartner = checkBPartners.get(0);
        		}
        	}
        	// Many results only cross check
        	if (checkBPartners.size()>1) {
        		
        		if (bpartner==null) {
        			for (int i=0; i<checkBPartners.size(); i++) {
        				currentPayment.addMessage("Possible bpMatch: " + checkBPartners.get(i).getValue() + " : " + checkBPartners.get(i).getName());
        			}
        			currentPayment.addMessage("Tax id: " + currentPayment.getTaxId());
        		} else {
        			boolean match = false;
        			for (int i=0; i<checkBPartners.size(); i++) {
        				if (checkBPartners.get(i).get_ID()==bpartner.get_ID()) {
        					// Match, no more worries
        					match = true;
        					continue;
        				}
        			}
        			// Check if matched
        			if (!match) {
        				// Let's not care for now.
        			}
        		}
        		
        	}
        }
        // END OF TAX ID CHECK
        
        // Update bpartner in payment extended record
        currentPayment.setBPartner(bpartner);
    	
    	StringBuffer description = new StringBuffer();
    	
        double payAmount = currentPayment.getOrderSum();
        boolean arReceipt = true;
        if (!currentPayment.isForceAsCustomerPayment() && currentPayment.getOrderSum()<0 && (invoice==null || (invoice!=null && !invoice.isSOTrx()))) {
        	arReceipt = false; // AP Payment
        }
        if (!arReceipt) {
        	payAmount = -payAmount;
        }
        
    	// If payment wasn't found using payment reference
    	if (payment==null) {
	        payment = new MPayment(ctx, 0, trxName);
	        payment.set_ValueOfColumn("AD_Client_ID", Integer.valueOf(m_clientId));
	        payment.setAD_Org_ID(m_orgId);
	        payment.setC_DocType_ID(arReceipt);
	        payment.setIsReceipt(arReceipt);
	        payment.setC_BankAccount_ID(ourBankAccountID);
	    	if (currentPayment.getBPartner()!=null) {
	    		payment.setC_BPartner_ID(currentPayment.getBPartner().get_ID());
	    	} else {
	    		payment.setC_BPartner_ID(m_unknownBPartnerId);
	    	}
	        payment.setC_Currency_ID(currencyId);
	        payment.setC_ConversionType_ID(114); // MConversionType.TYPE_SPOT
	        payment.setTenderType("T");
	        if (paymentReference!=null) {
	        	payment.setR_PnRef(paymentReference);
	        }
	        // try to match to sales order
	        if (currentPayment.getOrderNo()!=null) {
	        	MOrder o = new Query(ctx, MOrder.Table_Name, "documentno=? AND AD_Client_ID=?", trxName)
	        			.setParameters(new Object[]{currentPayment.getOrderNo(), Env.getAD_Client_ID(ctx)})
	        			.first();
	        	if (o!=null && o.get_ID()>0) {
	        		payment.setC_Order_ID(o.getC_Order_ID());
	        	}
	        }
	        System.out.println("Order: " + payment.getC_Order().getDocumentNo());
	        if (currentPayment.getOrderNo()!=null)
	        	description.append(currentPayment.getOrderNo());
	        if (currentPayment.getBpInvoiceNo()!=null) {
	        	if (description.length()>0) description.append(" ");
	        	description.append(currentPayment.getBpInvoiceNo());
	        }
	        if (currentPayment.getName()!=null && currentPayment.getName().trim().length()>0) {
	        	if (description.length()>0) description.append(" ");
	        	description.append(currentPayment.getName());
	        }
	        if (currentPayment.getTaxId()!=null && currentPayment.getTaxId().trim().length()>0) {
	        	if (description.length()>0) description.append(" ");
	        	description.append(currentPayment.getTaxId());
	        }
	        if (currentPayment.getDescription()!=null && currentPayment.getDescription().trim().length()>0) {
	        	if (description.length()>0) description.append(" ");
	        	description.append(currentPayment.getDescription());
	        }
	        payment.setDateAcct(new Timestamp(currentPayment.getTrxDate().getTime()));
	        payment.setDateTrx(new Timestamp(currentPayment.getTrxDate().getTime()));
	        payment.setAmount(currencyId, new BigDecimal(Double.toString(payAmount)));
	        // Set invoice ID if the invoice has an open amount
	        // If there's no open amount, don't set invoice but add a comment.
	        if (invoice!=null) {
		        payment.set_CustomColumn("IsOCR", invoice.get_Value("IsOCR"));
		        double openAmount = invoice.getOpenAmt().doubleValue();
		        if (openAmount!=0) {
		        	payment.setC_Invoice_ID(invoice.get_ID());
			        double pmtDiff = openAmount - payAmount;
			        if (pmtDiff!=0) {
						if (Math.abs(pmtDiff)>m_amountThreshold) {
							payment.setOverUnderAmt(new BigDecimal(new Double(pmtDiff).toString()));
						} else {
							payment.setDiscountAmt(new BigDecimal(new Double(pmtDiff).toString()));
						}
			        }
		        } else {
		        	if (description.length()>0) description.append(" ");
		        	String msg = "Double pmt of invoice no: " + invoice.getDocumentNo();
		        	currentPayment.addMessage(msg);
		        	description.append(msg);
		        }
		        payment.setIsApproved(true);
		        currentPayment.setInvoice(invoice);
		        // Check if invoice no should be set
		        if (currentPayment.getInvoiceNo()==null || currentPayment.getInvoiceNo().trim().length()==0) {
		        	currentPayment.setInvoiceNo(invoice.getDocumentNo());
		        }
	        }
	        if (payment.getC_BPartner_ID()>0 && currentPayment.getBPartner()==null) {
	        	currentPayment.setBPartner(new MBPartner(ctx, payment.getC_BPartner_ID(), trxName));
	        }
	        if (currentPayment.getBPartner()==null) {
	        	throw new Exception("A payment must have a business partner. Have you defined the unknown payer BPartner?");
	        }
	        payment.setDescription(description.toString());
	        currentPayment.setAdempierePayment(payment);
	        // Call before save and process
	        beforeSaveAndProcess(currentPayment);
	        try {
	        	payment.saveEx();
	        } catch (org.adempiere.exceptions.AdempiereException ee) {
	        	String errMsg = ee.getMessage()!=null ? ee.getMessage() : "";
	        	if (errMsg.contains("BP different from BP") && !errMsg.contains("BP Order")) {
	        		// The BP on the matched invoice differs - drop the invoice
	        		// reference and keep the payment. A "BP different from BP
	        		// Order" mismatch cannot be recovered this way (the order
	        		// reference is the conflict), so that one fails the run
	        		// with a description of the record instead.
	        		payment.addDescription(errMsg);
	        		payment.setC_Invoice_ID(0);
	        		try {
	        			payment.saveEx();
	        		} catch (org.adempiere.exceptions.AdempiereException e2) {
	        			throw new org.adempiere.exceptions.AdempiereException(
	        					describeFailedPayment(currentPayment, payment) + ": " + e2.getMessage(), e2);
	        		}
	        	} else {
	        		throw new org.adempiere.exceptions.AdempiereException(
	        				describeFailedPayment(currentPayment, payment) + ": " + errMsg, ee);
	        	}
	        }
    	} else {
    		// We have a payment already. Compare with the payment extended record
    		BigDecimal pamt = payment.getPayAmt();
    		double pmtDiff = pamt.doubleValue() - payAmount;
    		if (Math.abs(pmtDiff)>=m_amountThreshold) {
    			throw new Exception("The payment amount (" + payAmount + ") doesn't match with current payment " + payment.getDocumentNo() + " with amount " + pamt.doubleValue());
    		}
			if (payment.getC_Invoice_ID()!=0) {
				invoice = (MInvoice)payment.getC_Invoice();
				currentPayment.setInvoiceNo(invoice.getDocumentNo());
			}
    		currentPayment.setAdempierePayment(payment);
    		
    	}

    	// Make sure BP document number is updated if the information exists.
    	if (invoice!=null || payment!=null) {
    		if (invoice!=null)
    			invoice.load(trxName);
    		else {
    			invoice = (MInvoice)payment.getC_Invoice();
    			if (invoice!=null)
    				currentPayment.setInvoiceNo(invoice.getDocumentNo());
    		}
    		
    		if (invoice!=null && invoice.isComplete()) {
	    		String bpDocumentNo = invoice.get_ValueAsString("BpDocumentNo");
	    		if (currentPayment.bpInvoiceNo!=null && 
	    			currentPayment.bpInvoiceNo.trim().length()>0 &&
	    			(bpDocumentNo==null || bpDocumentNo.trim().length()==0))
	    		
	    		invoice.set_ValueOfColumn("BpDocumentNo", currentPayment.bpInvoiceNo);
	    		invoice.saveEx();
    		}
    	}
        
        return(currentPayment);
    }
    
	/**
	 * The payment records created. If the method createPayments hasn't been called, this will be
	 * empty.
	 * 
	 * @see 	createPayments()
	 * 
	 * @return
	 */
	public List<PaymentExtendedRecord> getPayments() {
		return(m_payments);
	}

	/**
	 * @return	A vector of not processed payments. These are the ones
	 * 			that haven't been matched. 
	 */
	public Vector<PaymentExtendedRecord> getNotProcessedPayments() {
		return(m_notProcessedPayments);
	}
	
	/**
	 * If dryRun is set to true, nothing is committed.
	 * 
	 * @param flag
	 */
	public void setDryRun(boolean flag) {
		m_dryRun = flag;
	}
	
	public boolean isDryRun() {
		return(m_dryRun);
	}
	
	public MBankAccount getBankAccount() {
		return(m_ourBa);
	}

	protected MBPartner lookupUnidentifiedBPartner() throws Exception {
		
		if (m_unknownBPartner==null || m_unknownBPartnerId!=m_unknownBPartner.get_ID()) {
			m_unknownBPartner = MBPartner.get(m_ctx, m_unknownBPartnerId);
		}
		
		return m_unknownBPartner;
		
	}
	
	/**
	 * Method to lookup Invoice using OCR number
	 * @param ctx
	 * @param ocr
	 * @return	The corresponding invoice
	 * @throws Exception
	 */
	protected MInvoice lookupInvoiceOCR(Properties ctx, String ocr) throws Exception {
		
		List<MInvoice> invoices = new Query(ctx, MInvoice.Table_Name, "OCR=?", null)
			.setParameters(new Object[]{ocr})
			.setApplyAccessFilter(true)
			.list();
		if (invoices.size()>1) {
			m_log.warning("Document No: " + ocr + " is ambigous and can't be matched.");
			return(null);
		}
		if (invoices.size()==0) return(null);
		return(invoices.get(0));
		
	}
	
	
	/**
	 * Method to lookup Invoice
	 * @param ctx
	 * @param documentNo
	 * @return
	 * @throws Exception
	 */
	protected MInvoice lookupInvoice(Properties ctx, String documentNo) throws Exception {
		
		List<MInvoice> invoices = new Query(ctx, MInvoice.Table_Name, "DocumentNo=?", null)
			.setParameters(new Object[]{documentNo})
			.setApplyAccessFilter(true)
			.list();
		if (invoices.size()>1) {
			m_log.warning("Document No: " + documentNo + " is ambigous and can't be matched.");
			return(null);
		}
		if (invoices.size()==0) return(null);
		return(invoices.get(0));
		
	}
	
	/**
	 * Method to lookup Invoice
	 * @param ctx
	 * @param orderId
	 * @param bpDocumentNo
	 * @return
	 * @throws Exception
	 */
	protected MInvoice lookupInvoiceUsingOrderIdAndBpDocumentNo(Properties ctx, int orderId, String bpDocumentNo) throws Exception {
		
		List<MInvoice> invoices = new Query(ctx, MInvoice.Table_Name, "C_Order_ID=? AND BpDocumentNo=?", null)
			.setParameters(new Object[]{orderId, bpDocumentNo})
			.setApplyAccessFilter(true)
			.list();
		if (invoices.size()>1) {
			m_log.warning("BP Document No: " + bpDocumentNo + " for order_ID " + orderId + " is ambigous and can't be matched.");
			return(null);
		}
		if (invoices.size()==0) return(null);
		return(invoices.get(0));
		
	}
	protected MInvoice lookupInvoiceUsingOrderIdAndTotalSum(Properties ctx, int orderId, Double totalSum) throws Exception {
		
		List<MInvoice> invoices = new Query(ctx, MInvoice.Table_Name, "C_Order_ID=? AND isPaid = 'N' ", null)
			.setParameters(new Object[]{orderId})
			.setApplyAccessFilter(true)
			.setOrderBy(" created asc ")
			.list();
		if (invoices!=null && invoices.size()==1)
			return invoices.get(0);
		if (invoices.size()>1) {
			for (MInvoice inv : invoices) {
				
				Double grandTot = inv.getGrandTotal().doubleValue();
				Double diff = Math.abs(grandTot - totalSum);
				if (diff < 1) {
					return inv;
				}
			}
		}
		
		//if (invoices.size()>1) {
		//	m_log.warning("BP Document No: " + "" + " for order_ID " + orderId + " is ambigous and can't be matched.");
		//	return(null);
		//}
		if (invoices.size()==0) return(null);
		return(invoices.get(0));
		
	}
	
	/**
	 * Method to lookup Invoice
	 * @param ctx
	 * @param pExt
	 * @param soTrx
	 * @return
	 * @throws Exception
	 */
	protected MInvoice lookupInvoiceUsingBpDocumentNo(Properties ctx, PaymentExtendedRecord pExt, boolean soTrx) throws Exception {

		List<MInvoice> invoices = null;
		
		// Match currency
		if (pExt.getCurrency()!=null) {
			MCurrency currency = MCurrency.get(ctx, pExt.getCurrency());
			if (currency!=null) {
				 invoices = new Query(ctx, MInvoice.Table_Name, " BpDocumentNo=? AND AD_Client_ID=? AND IsSOTrx=? AND C_Currency_ID=?", null)
					.setParameters(new Object[]{pExt.getBpInvoiceNo(), Env.getAD_Client_ID(ctx), soTrx ? "Y" : "N", currency.get_ID()})
					.setOnlyActiveRecords(true)
					.list();
			}
		}

		// Try without currency since it's not supplied or faulty
		if (invoices==null) {
			 invoices = new Query(ctx, MInvoice.Table_Name, " BpDocumentNo=? AND AD_Client_ID=? AND IsSOTrx=?", null)
				.setParameters(new Object[]{pExt.getBpInvoiceNo(), Env.getAD_Client_ID(ctx), soTrx ? "Y" : "N"})
				.setOnlyActiveRecords(true)
				.list();
		}
		
		if (invoices.size()>1) {
			m_log.warning("BP Document No: " + pExt.getBpInvoiceNo() + " is ambigous and can't be matched.");
			return(null);
		}
		if (invoices.size()==0) {
			return(null);
		}
		return(invoices.get(0));
	}
	
	/**
	 * Method to lookup Invoice
	 * @param ctx
	 * @param pExt
	 * @param soTrx
	 * @return
	 * @throws Exception
	 */
	protected MInvoice lookupInvoiceUsingBpCustomerNoAndBpDocumentNo(Properties ctx, PaymentExtendedRecord pExt, boolean soTrx) throws Exception {

		List<MInvoice> invoices = null;
		
		// Match currency
		if (pExt.getCurrency()!=null) {
			MCurrency currency = MCurrency.get(ctx, pExt.getCurrency());
			if (currency!=null) {
				 invoices = new Query(ctx, MInvoice.Table_Name, " BpCustomerNo=? AND BpDocumentNo=? AND AD_Client_ID=? AND IsSOTrx=? AND C_Currency_ID=?", null)
					.setParameters(new Object[]{pExt.getBpCustomerNo(), pExt.getBpInvoiceNo(), Env.getAD_Client_ID(ctx), soTrx ? "Y" : "N", currency.get_ID()})
					.setOnlyActiveRecords(true)
					.list();
			}
		}

		// Try without currency since it's not supplied or faulty
		if (invoices==null) {
			 invoices = new Query(ctx, MInvoice.Table_Name, " BpCustomerNo=? AND BpDocumentNo=? AND AD_Client_ID=? AND IsSOTrx=?", null)
				.setParameters(new Object[]{pExt.getBpCustomerNo(), pExt.getBpInvoiceNo(), Env.getAD_Client_ID(ctx), soTrx ? "Y" : "N"})
				.setOnlyActiveRecords(true)
				.list();
		}
		
		if (invoices.size()>1) {
			m_log.warning("BP Document No: " + pExt.getBpInvoiceNo() + " is ambigous and can't be matched.");
			return(null);
		}
		if (invoices.size()==0) {
			return(null);
		}
		return(invoices.get(0));
	}
	

	/**
	 * Method to lookup order using order no.
	 * 
	 * @param ctx
	 * @param documentNo
	 * @return
	 * @throws Exception
	 */
	protected MOrder lookupOrder(Properties ctx, String documentNo) throws Exception {

		List<MOrder> orders = new Query(ctx, MOrder.Table_Name, "DocumentNo=? AND docstatus NOT IN ('VO') ", null)
		.setParameters(new Object[]{documentNo})
		.setApplyAccessFilter(true)
		.list();
		if (orders.size()>1) {
			m_log.warning("Document No: " + documentNo + " is ambigous and can't be matched.");
			return(null);
		}
		if (orders.size()==0) return(null);
		return(orders.get(0));
		
	}
	
	/**
	 * Method to lookup Invoice using order No
	 * 
	 * @param ctx
	 * @param orderNo
	 * @return
	 * @throws Exception
	 */
	protected MInvoice lookupInvoiceForOrder(Properties ctx, String orderNo) throws Exception {

		List<MInvoice> invoices = new Query(ctx, MInvoice.Table_Name, "C_Order_ID=(select C_Order_ID from C_Order where C_Order.DocumentNo=?)", null)
		.setParameters(new Object[]{orderNo})
		.setApplyAccessFilter(true)
		.list();
		if (invoices.size()>1) {
			m_log.warning("Order No: " + orderNo + " is ambigous and can't be matched.");
			return(null);
		}
		if (invoices.size()==0) return(null);
		return(invoices.get(0));
		
	}

	/**
	 * Looks up a business partner using tax id.
	 * 
	 * @param taxId
	 * @return A list of matching business partners.
	 */
	public List<MBPartner> lookupBpUsingTaxId(Properties ctx, String taxId, String trxName) {
		
		if (taxId==null)
			return null;
		
		List<MBPartner> list = null;
		list = new Query(ctx, MBPartner.Table_Name, "TaxId=? AND AD_Client_ID=?", trxName)
					.setParameters(taxId, Env.getAD_Client_ID(ctx))
					.setOnlyActiveRecords(true)
					.list();
		
		return list;
	}
	
	
	/**
	 * Creates payments. The source information is fetched by calling the subclass' method getSourcePayments.
	 * 
	 * @param props
	 * @return
	 * @throws Exception
	 */
	protected List<PaymentExtendedRecord> createPayments(Properties props) throws Exception {

		// Reset not processed payments
		m_notProcessedPayments.clear();
		
		List<PaymentExtendedRecord> records = null;
		try {
			records = getSourcePayments(props);
		} catch (Exception e) {
			// Print strack trace for easier debugging
			e.printStackTrace();
			throw e;
		}
		
		if (records.size()==0) return records;
		
		m_log.fine("Creating " + records.size() + " payments from " + this.getFileName());
		if (m_dryRun)
			m_log.fine("DRY RUN! Payments won't be saved.");
		
		// Check number of active transactions before starting
		if (!m_dryRun) {
			Trx[] activeTrx = Trx.getActiveTransactions();
			int maxTrxCount = Ini.isClient() ? 5 : 20;
			if (activeTrx.length>maxTrxCount) {
				throw new Exception(
						"Too many active transactions to safely start reading a payment file. Active # " 
							+ activeTrx.length + " : Max allowed # " + maxTrxCount);
			}
		}
		
		// Check for dry run check override
		boolean disableDryRunCheck = props!=null && props.containsKey(HeadlessPaymentPlugin.DISABLE_DRY_RUN_CHECK) 
				&& "Y".equals(props.getProperty(HeadlessPaymentPlugin.DISABLE_DRY_RUN_CHECK));
		
		if (disableDryRunCheck && !m_dryRun) {
			m_log.warning("Dry run limit disabled.");
		}
		
		String 		trxName = Trx.createTrxName();
		Vector<PaymentExtendedRecord>	paymentsToProcess = new Vector<PaymentExtendedRecord>();
		MPayment payment;
		MInvoice	feeInvoice;
		boolean feeInvoiceOk;
		boolean paymentOk;
		int			ourBankAccountID = m_ourBa.get_ID();
		int			currencyId = m_ourBa.getC_Currency_ID();
		String		currencyIso = m_ourBa.getC_Currency().getISO_Code().toUpperCase();
		double totalPaymentAndFee = 0.0;
		double totalInvoice = 0.0;
		String		customBankAccountCurrency = null;
		int			customCurrencyId = 0;
		
		// Create payments
		// Iterate through the sets
		for (PaymentExtendedRecord rec : records) {

			if (rec.getBankAccountPtr()!=null) {
				customBankAccountCurrency = rec.getBankAccountPtr().getC_Currency().getISO_Code().toUpperCase();
				customCurrencyId = rec.getBankAccountPtr().getC_Currency_ID();
			} else {
				customBankAccountCurrency = null;
				customCurrencyId = 0;
			}
			
			// Compare currency, must be null or the same as the bank account's.
			if (rec.getCurrency()!=null && !rec.getCurrency().equalsIgnoreCase(customBankAccountCurrency!=null ? customBankAccountCurrency : currencyIso)) {
				DB.rollback(false, trxName);
				Trx t = Trx.get(trxName, false);
				if (t!=null)
					t.close();
				throw new Exception("Currency of payment (" + rec.getCurrency() + ") doesn't match currency of bank account (" + currencyIso + ").");
			}
			
            // Add payment to list of payments to process
            rec = createPayment(m_ctx, rec, trxName, 
            					rec.getBankAccountPtr()!=null ? rec.getBankAccountPtr().get_ID() : ourBankAccountID,	// Use bank account on payment if existing 
            					customCurrencyId!=0 ? customCurrencyId : currencyId);
            // Create fee invoice if applicable
            feeInvoice = getFeeInvoice(m_ctx, props, rec, trxName);
            if (feeInvoice!=null) {
            	totalPaymentAndFee = rec.getAdempierePayment().getPayAmt().doubleValue() + feeInvoice.getGrandTotal().doubleValue();
            } else {
            	totalPaymentAndFee = rec.getAdempierePayment().getPayAmt().doubleValue();
            }
            totalInvoice = rec.getInvoice()!=null ? rec.getInvoice().getOpenAmt().doubleValue() : 0.0;
            
            // Create fee payment charges if applicable
            createFeePayments(m_ctx, rec, trxName);
            
            // Only process the payments if we have a business partner associated with the payment,
            // (and the business partner is identified and the invoice is attached unless forceComplete)

            // COMPLETE CHECKING, IE CHECK IF WE CAN COMPLETE
            // NOT COMPLETED PAYMENTS SIGNAL THAT MANUAL ATTENTION IS NEEDED
            boolean bPartnerIdentified = rec.getAdempierePayment().getC_BPartner_ID()!=m_unknownBPartnerId && rec.getAdempierePayment().getC_BPartner_ID()!=0;
            boolean invoiceIdentified = rec.getAdempierePayment().getC_Invoice_ID()>0;
            boolean amountMatch = !rec.getAdempierePayment().isOverUnderPayment() || totalInvoice==totalPaymentAndFee;
            boolean canComplete = false;

            if (!bPartnerIdentified && rec.getTaxId()!=null) {
            	// Append tax id to comment
            	rec.getAdempierePayment().addDescription(rec.getTaxId());
            }
            
            if (m_autoComplete && bPartnerIdentified) {
            	// Try to complete
            	if (invoiceIdentified) {
            		if (!amountMatch) {
            			if (feeInvoice==null) {
            				canComplete = false; // We can't use amount threshold twice.
            				rec.addMessage("Payment doesn't match invoice open amount");
            			} else {
	            			// Check how much difference we have and if it is because of a fee invoice
	            			double diff = totalPaymentAndFee-totalInvoice;
	        		        if (diff!=0) {
	        		        	double payAmt = rec.getAdempierePayment().getPayAmt().doubleValue();
	        					if (Math.abs(diff)<=0.1) {	// TODO: Make configurable
	        						payAmt = (double)Math.round((payAmt-diff)*100.0)/100.0d;
	        						rec.getAdempierePayment().setPayAmt(BigDecimal.valueOf(payAmt));
	        						canComplete = true;
	        					} else {
	        						// Too big difference
	        						canComplete = false;
	        						rec.addMessage("Amount doesn't match. Too large rounding error.");
	        					}
	        		        } else {
	        		        	// This shouldn't happen
	        		        	canComplete = false;
	        		        }
            			}
            			
            		} else {
            			// Amount matches, complete
            			canComplete = true;
            		}
            	} else {
            		// Invoice not identified
            		// We don't complete if only bPartner is identified because allocation must be done manually anyway.
            		// If the force complete flag is set, then we complete anyway.
            		canComplete = rec.isForceComplete() || (
            				rec.getAdempierePayment()!=null && MPayment.DOCSTATUS_Completed.equals(rec.getAdempierePayment().getDocStatus()));
            	}
            	
            };
            
            if (canComplete) {
            	paymentsToProcess.add(rec);
            } else {
            	m_notProcessedPayments.add(rec);
            }
            rec.setBPartnerIdentified(bPartnerIdentified);
            // If dry run, set processed = true
            if (m_dryRun) {
            	rec.setProcessed(true);
            }
				
		}
		if (!disableDryRunCheck) {
			// Safety measure I
			if (paymentsToProcess.size()<m_notProcessedPayments.size() && (paymentsToProcess.size()+m_notProcessedPayments.size())>2) {
				if (Ini.isClient()) {
					JOptionPane.showMessageDialog(null, "The number of payments to process is smaller than the number of not processed payments. Setting to dry run (nothing is saved)");
				}
				m_dryRun = true;
				
			}
			// Safety measure II
			if (m_notProcessedPayments.size() > MAX_UNCOMPLETED_PAYMENTS) {
				if (Ini.isClient()) {
				JOptionPane.showMessageDialog(null, "The number of not processed payments is higher than max allowed (" + MAX_UNCOMPLETED_PAYMENTS + "). " + 
													"Setting to dry run (nothing is saved)");
				}
				m_dryRun = true;
			}
		}
		
		// Commit changes
		if (!m_dryRun) {
			DB.commit(true, trxName);
		} else {
			DB.rollback(false, trxName);
		}
		
		// If commit works fine, continue to process matched payments (so we don't have to process them
		// manually)
		if (!m_dryRun) {
			for (PaymentExtendedRecord rec : paymentsToProcess) {
				
				// Complete the main payment 
				payment = rec.getAdempierePayment();
				if (payment!=null) {
					if (!(
							MPayment.DOCSTATUS_Completed.equals(payment.getDocStatus()) ||
							MPayment.DOCSTATUS_Closed.equals(payment.getDocStatus()))) {
						try {
							paymentOk = payment.processIt(DocAction.ACTION_Complete);
							if (paymentOk) {
								payment.saveEx(trxName);
							}
						} catch (Exception e) {
							paymentOk = false;
							if (Ini.isClient())
								JOptionPane.showMessageDialog(null, e.getMessage() + " Payment: " + payment.getDocumentNo());
							e.printStackTrace();
						}
					} else {
						paymentOk = true; // Already completed
					}
				} else {
					paymentOk = false;
				}
				// Complete the fee invoice
				feeInvoice = rec.getFeeInvoice();
				if (feeInvoice!=null) {
					try {
						feeInvoiceOk = feeInvoice.processIt(DocAction.ACTION_Complete);
						if (feeInvoiceOk) {
							feeInvoice.saveEx(trxName);
						}
					} catch (Exception e) {
						feeInvoiceOk = false;
						if (Ini.isClient())
							JOptionPane.showMessageDialog(null, e.getMessage() + " Invoice: " + feeInvoice.getDocumentNo());
						e.printStackTrace();
					}
				} else {
					feeInvoiceOk = false;
				}
				
				// Complete any fee payments
				MPayment feePmt;
				boolean feePmtOk = false;
				if (rec.getPaymentFees()!=null && rec.getPaymentFees().size()>0) {
					for (PaymentFeeRecord r : rec.getPaymentFees()) {
						feePmt = r.getFeePayment();
						if (feePmt!=null) {
							if (!MPayment.DOCSTATUS_Completed.equals(feePmt.getDocStatus()) ||
									!MPayment.DOCSTATUS_Closed.equals(feePmt.getDocStatus())) {
								try {
									feePmtOk = feePmt.processIt(DocAction.ACTION_Complete);
									if (feePmtOk) {
										feePmt.saveEx(trxName);
									}
								} catch (Exception e) {
									feePmtOk = false;
									if (Ini.isClient())
										JOptionPane.showMessageDialog(null, e.getMessage() + " Payment: " + payment.getDocumentNo());
									e.printStackTrace();
								}
							} else {
								feePmtOk = true; // Already completed
							}
						}
					}
				}
				
				// Must commit here to prevent deadlock when allocating.
				DB.commit(true, trxName);
				// Try to allocate if there's a fee invoice
				boolean allocateOk = true;
				if (paymentOk && feeInvoiceOk && !rec.getInvoice().isPaid()) {
					
					MAllocationHdr allocHdr = createAllocation(rec.getInvoice(), rec.getFeeInvoice());
					try {
						boolean allocOk = allocHdr.processIt(DocAction.ACTION_Complete);
						if (allocOk) {
							allocHdr.saveEx(trxName);
							DB.commit(true, trxName);
						} else {
							rec.addMessage(allocHdr.getDocumentNo() + " was not completed.");
							DB.rollback(true, trxName);
							allocateOk = false;
						}
					} catch (AdempiereException ae) {
						rec.addMessage(allocHdr.getDocumentNo() + " was not completed.");
						ae.printStackTrace();
						DB.rollback(true, trxName);
						allocateOk = false;
					}
					
				}
				
				// Set processed flag
				if (paymentOk && allocateOk) {
					rec.setProcessed(true);
				}
				
			}
			
		} else {
			// Dry run, set records in not processed payments to not processed
			for (PaymentExtendedRecord r : m_notProcessedPayments) {
				r.setProcessed(false);
			}
		}

		/** 
		 *  Make sure that the transaction we opened in the beginning 
		 *  is closed.
		 */
		Trx[] trx = Trx.getActiveTransactions();
		if (trx!=null) {
			for (int i=0; i<trx.length; i++) {
				if (trxName.equalsIgnoreCase(trx[i].getTrxName())) {
					trx[i].close();
				}
			}
		}
		return(records);
		
	}

	/**
	 * TODO: Complete
	 * 
	 * @param customerInvoice
	 * @param feeInvoice
	 * @return
	 */
	private MAllocationHdr createAllocation(MInvoice customerInvoice, MInvoice feeInvoice) {
		
		// If both are allocated/paid, do nothing
		if (customerInvoice.isPaid() && feeInvoice.isPaid()) return null;
		
		MAllocationHdr hdr = new MAllocationHdr(customerInvoice.getCtx(), false, feeInvoice.getDateAcct(), 
				feeInvoice.getC_Currency_ID(), "Auto match payment fee invoice " + feeInvoice.getDocumentNo(), customerInvoice.get_TrxName());
		hdr.setAD_Org_ID(feeInvoice.getAD_Org_ID());
		hdr.setIsApproved(true);
		hdr.saveEx();
		
		BigDecimal customerInvoiceAmt = customerInvoice.getOpenAmt();
		if (customerInvoice.isCreditMemo())
			customerInvoiceAmt = customerInvoiceAmt.negate();
		BigDecimal feeInvoiceAmt = feeInvoice.getOpenAmt();
		if (feeInvoice.isCreditMemo()) {
			feeInvoiceAmt = feeInvoiceAmt.negate();
		}
		
		//	New Allocation Line
		BigDecimal amount = feeInvoiceAmt.min(customerInvoiceAmt);
		MAllocationLine aLine = new MAllocationLine (hdr, amount, 
				BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		aLine.setC_BPartner_ID(customerInvoice.getC_BPartner_ID());
		aLine.setC_Invoice_ID(customerInvoice.get_ID());
		aLine.saveEx();
		
		aLine = new MAllocationLine (hdr, amount.negate(), 
				BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
		aLine.setC_BPartner_ID(feeInvoice.getC_BPartner_ID());
		aLine.setC_Invoice_ID(feeInvoice.get_ID());
		aLine.saveEx();
		
		return hdr;
	}
	
	/**
	 * Starts the payment process
	 *  
	 * @return
	 * @throws Exception
	 */
	public List<PaymentExtendedRecord> createPayments() throws Exception {
		m_payments = createPayments(m_properties);
		return m_payments;
	}
	
	
	/**
	 * Method used primarily for testing purposes. Good to use when the parsing of source
	 * payments need to be tested.
	 * 
	 * @return
	 * 			Source payments only without enriching / trying to create payments
	 * 			from the records.
	 * @throws Exception
	 */
	public List<PaymentExtendedRecord> createSourcePayments() throws Exception {
		return getSourcePayments(m_properties);
	}
	
	/**
	 * Convenience method to be able to run createPayments in a separate thread.
	 * 
	 * @see createPayments
	 */
	@Override
	public void run() {

		try {
			createPayments();
		} catch (Exception ee) {
			throw new RuntimeException(ee);
		}
		
	}
	
	
}
