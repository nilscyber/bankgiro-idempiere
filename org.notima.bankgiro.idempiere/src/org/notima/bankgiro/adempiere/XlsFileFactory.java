package org.notima.bankgiro.adempiere;

import java.io.File;
import java.io.FileOutputStream;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.compiere.model.MBankAccount;
import org.compiere.model.MInvoice;
import org.compiere.util.Env;
import org.compiere.util.KeyNamePair;
import org.compiere.util.Msg;
import org.notima.bankgiro.adempiere.model.MLBSettings;
import org.notima.bg.BgUtil;

/**
 * File factory that creates an XLS file listing the payments.
 * 
 * Used to create report of payments sent.
 * 
 * @author Daniel Tamm
 *
 */
public class XlsFileFactory implements PaymentFileFactory {

	public static String KEY_XLS_FILE = "KEY_XLS_FILE";	
	
	private MBankAccount bankAccount;
	private List<LbPaymentRow> payments;
	private SortedMap<String, MLBSettings> m_lbSettings;
	private File outDir;	
	private String 	lbEmptyRef;
	private DateFormat	df		= new SimpleDateFormat("yyyyMMdd.HHmmss");
	
	public String[] colNames = {
	        "Business Partner",
	        "Invoice ID",
	        "OCR / Ref",
	        "Dst Acct",
	        "Invoice Date",
	        "Due Date",
	        "Pay Date",
	        "Amount Due",
	        "Payment Amount",
	        "Currency",
	};
	
	public int[] colWidth = {
			8000,
			3800,
			5000,
			5000,
			3500,
			3500,
			3500,
			3500,
			3500,
			3000
	};
	
	@Override
	public File createPaymentFile(MBankAccount srcAccount,
			List<LbPaymentRow> suggestedPayments, File outDir, String trxName) {
		bankAccount = srcAccount;
		payments = suggestedPayments;
		this.outDir = outDir;
		m_lbSettings = PluginRegistry.registry.getLbSettings();
		MLBSettings tmpSet = m_lbSettings.get(MLBSettings.LB_EMTPY_REF);
		if (tmpSet!=null) {
			lbEmptyRef = tmpSet.getName();
		}
		return createPaymentFile();
	}

	@Override
	public PaymentValidator getPaymentValidator() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getKey() {
		return KEY_XLS_FILE;
	}

	@Override
	public String getName() {
		return "XLS File Factory";
	}
	
    /**
     * Creates payment report file in XLS-format
     * 
     * @return	True if creation succeeded
     */
    private File createPaymentFile() {
    	
        MBankAccount senderBankAccount = bankAccount;
        String srcBankAccountCurrency = senderBankAccount.getC_Currency().getISO_Code();
        // Set sender accounts
        // If the bank account is marked as isBankgiro the sending bankgiro is the same as accountNO.
        // If the bank account is not marked as bankgiro the sending bankgiro is the AD_Bankgiro field.
        boolean isBankgiro = (Boolean)senderBankAccount.get_Value("IsBankgiro");
        String bankAccountNo = senderBankAccount.getAccountNo();
        String AD_Bankgiro = isBankgiro ? bankAccountNo : senderBankAccount.get_ValueAsString("AD_Bankgiro");
        bankAccountNo = BgUtil.toDigitsOnly(bankAccountNo);
		// Clean up number
		AD_Bankgiro = BgUtil.toDigitsOnly(AD_Bankgiro);
		boolean validBankgiro = BgUtil.validateBankgiro(AD_Bankgiro);
		if (!validBankgiro)
		{
			MessageCenter.error(null, Msg.getMsg(Env.getCtx(),"BankgiroProblem"), Msg.getMsg(Env.getCtx(),"BGClientNotValid"));
			return null;
		}

        // Iterate through all rows in the table
        List<LbPaymentRow> checkedRows = payments;
        Timestamp dueDate;
        
        HSSFWorkbook wb = new HSSFWorkbook();

        Date now = Calendar.getInstance().getTime();
        
        // Create new sheet
        Sheet sheet = wb.createSheet(df.format(now));
        
        LbPaymentRow row;
        String currency;
        Cell cell;
        Row	 xrow;
        String theirRef;
        int		rowNo = 0;
        short		cellNo = 0;
        
        
        // Date style
        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
        
        // Header style
        CellStyle headerStyle = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        headerStyle.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        headerStyle.setFont(font);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        headerStyle.setWrapText(true);
        
        // Amount style
        CellStyle amtStyle = wb.createCellStyle();
        amtStyle = wb.createCellStyle();
        amtStyle.setDataFormat(wb.createDataFormat().getFormat("#,###"));
        
        // Create header
    	xrow = sheet.createRow(rowNo++);
        for (int i=0; i<colNames.length; i++) {
        	cell = xrow.createCell(i);
        	cell.setCellValue(colNames[i]);
        	cell.setCellStyle(headerStyle);
        	sheet.setColumnWidth((short)i, (short)colWidth[i]);
        }
        
		for (Iterator<LbPaymentRow> it = checkedRows.iterator(); it.hasNext();)
		{
			row = it.next();
            // Read the selected invoice
            MInvoice invoice = row.getInvoice();
            KeyNamePair bPartnerKey = row.getBpartner().getKeyNamePair();
            dueDate = row.payDate;
            currency = invoice.getCurrencyISO();

            // Create xls row
            xrow = sheet.createRow(rowNo++);
            // Write payment info
            cellNo = 0;
            cell = xrow.createCell(cellNo++);
            cell.setCellValue(row.getBpartner().getName());
            
            cell = xrow.createCell(cellNo++);
            cell.setCellValue(row.getInvoice().getDocumentNo());
            
            if (row.getInvoice().get_Value("IsOCR")!=null && ((Boolean)row.getInvoice().get_Value("IsOCR")).booleanValue()) {
				theirRef = row.getInvoice().get_ValueAsString("OCR");
			} else {
				theirRef = row.getInvoice().get_ValueAsString("BPDocumentNo");
			}            
            cell = xrow.createCell(cellNo++);
            cell.setCellValue(theirRef);

            cell = xrow.createCell(cellNo++);
            cell.setCellValue(row.dstAcct);

            cell = xrow.createCell(cellNo++);
            cell.setCellValue(new java.util.Date(row.getInvoice().getDateInvoiced().getTime()));
            cell.setCellStyle(dateStyle);
            
            cell = xrow.createCell(cellNo++);
            cell.setCellValue(dueDate!=null ? new java.util.Date(dueDate.getTime()) : null);
            cell.setCellStyle(dateStyle);

            cell = xrow.createCell(cellNo++);
            cell.setCellValue(row.payDate!=null ? new java.util.Date(row.payDate.getTime()) : null);
            cell.setCellStyle(dateStyle);

            cell = xrow.createCell(cellNo++);
            cell.setCellValue(row.amountDue);
            cell.setCellStyle(amtStyle);
            
            cell = xrow.createCell(cellNo++);
            cell.setCellValue(row.payAmount);
            cell.setCellStyle(amtStyle);

            cell = xrow.createCell(cellNo++);
            cell.setCellValue(row.currency);
            
    	}   //  for all rows in table
		
        // Write file
        String outputDir = outDir.getAbsolutePath();

        if (!outputDir.endsWith(File.separator)) outputDir += File.separator;
        File xlsFile = new File(outputDir + "Report-" + df.format(now) + ".xls");
        if (xlsFile.exists()) {
            xlsFile.delete();
        }
        try {
        	
        	// Write file
        	FileOutputStream fileOut = new FileOutputStream(xlsFile);
        	wb.write(fileOut);
        	fileOut.close();
        	
        	// If windows
        	if (Env.isWindows()) {
        		
        		ProcessBuilder pb = new ProcessBuilder();
        		List<String> cmds = new ArrayList<String>();
        		cmds.add("rundll32");
        		cmds.add("shell32.dll,ShellExec_RunDLL");
        		cmds.add(xlsFile.getAbsolutePath());
        		pb.command(cmds);
        		pb.start();
        		
        	}
            
        } catch (Exception ioe) {
        	MessageCenter.error(null, "Unexpected error", ioe.getMessage());
            ioe.printStackTrace();
            return(null);
        }

		return xlsFile;

    }
	

}
