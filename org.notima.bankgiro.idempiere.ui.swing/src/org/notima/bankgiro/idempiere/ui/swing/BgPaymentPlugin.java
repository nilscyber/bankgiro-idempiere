package org.notima.bankgiro.idempiere.ui.swing;

import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;

import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;

import org.compiere.model.MBankAccount;
import org.compiere.util.Env;
import org.notima.bankgiro.adempiere.CreateReceivablesPayments;
import org.notima.bankgiro.adempiere.PaymentExtendedRecord;
import org.notima.bankgiro.adempiere.PaymentFactory;
import org.notima.bankgiro.adempiere.form.BgPanel;
import org.notima.bankgiro.adempiere.form.I_LBSettingsPanel;
import org.notima.bankgiro.adempiere.form.VLBManagementPanel;
import org.notima.bankgiro.adempiere.model.MLBFile;
import org.notima.bankgiro.adempiere.model.MLBPlugin;
import org.notima.bankgiro.adempiere.model.MLBSettings;
import org.notima.bg.BgFile;
import org.notima.bg.BgMaxFile;
import org.notima.bg.BgSet;

/**
 * Plugin class for creating payments from BG/PG files (BGMax).
 * The class must check the MLBSettings.BG_DRYRUN_FLAG  
 * 
 * @author Daniel Tamm
 *
 */
public class BgPaymentPlugin extends PaymentPlugin {

	private Action						m_action;
	private CreateReceivablesPayments	m_process;	
	private MLBPlugin					m_plugin;
	
	public BgPaymentPlugin(Properties ctx, VLBManagementPanel frame) throws Exception {
		super(ctx, frame);
		// Create action
        m_action = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                createPayments();
            }
        };
        m_action.putValue(Action.NAME, "BG/PG receivables file");
        m_action.putValue(Action.SHORT_DESCRIPTION, "Reads BG/PG receivables file");
        
        // Read plugin information
        m_plugin = MLBPlugin.getPluginByClass(m_ctx, BgPaymentPlugin.class, null);
	}
	
	public Action[] getActions() {
		return(new Action[]{m_action});
	}
	
	private void createPayments() {
		
		MBankAccount targetBa = m_frame.getSelectedBankAccount();
		
    	MLBSettings unknownPayer = m_lbSettings.get(MLBSettings.BG_UNKNOWN_PAYER_BPID);
    	if (unknownPayer==null) {
    		JOptionPane.showMessageDialog(m_frame, "The unknown payer BP must be set!");
    		return;
    	}
    	int unknownPayerId = new Integer(unknownPayer.getName()).intValue();
        MLBSettings lastDir = m_lbSettings.get(MLBSettings.BG_RECEIVABLES_DIR);
    	JFileChooser fc;
    	if (lastDir!=null) {
    		fc = new JFileChooser(lastDir.getName());
    	} else {
    		fc = new JFileChooser();
    	}
        // Select file to open
        int retVal = fc.showDialog(m_frame, "Select receivables file");
        if (retVal==JFileChooser.APPROVE_OPTION) {
            File reportFile = fc.getSelectedFile();
            // Save last directory
            try {
            	String lastDirStr = fc.getSelectedFile().getParentFile().getCanonicalPath();
            	if (lastDir==null) {
            		lastDir = new MLBSettings(m_ctx, 0, null);
            		lastDir.setValue(MLBSettings.BG_RECEIVABLES_DIR);
            	}
            	lastDir.setName(lastDirStr);
            	lastDir.saveEx();

            } catch (java.io.IOException ioe) {
            }
            
            // Check if this file has been read before (only check name)
            MLBFile matchingFile = MLBFile.findByName(m_ctx, m_plugin.get_ID(), reportFile.getName(), null);
            if (matchingFile!=null) {
            	retVal = JOptionPane.showConfirmDialog(m_frame, 
            			"This file appears to have already been read at " + matchingFile.getCreated() +  
            			"\nRead file anyway?",
            			"Read file again?", 
            			JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            	if (retVal!=JOptionPane.OK_OPTION) return;
            }
            
            // Read the report file
            BgFile inFile = new BgMaxFile();
            Charset cs = Charset.forName("ISO8859-1"); // Default (other options Cp850)
            MLBSettings charSetSetting = m_lbSettings.get(MLBSettings.BG_FILE_CODEPAGE);
            // If another is set, use that
            if (charSetSetting!=null) {
            	String tmpCharSetStr = charSetSetting.getName();
            	if (tmpCharSetStr!=null && tmpCharSetStr.trim().length()>0) {
            		try {
            			cs = Charset.forName(tmpCharSetStr);
            		} catch (Exception eee) {
            			m_log.warning("Invalid charset " + tmpCharSetStr + " for " + charSetSetting.getValue());
            		}
            	}
            }
            try {
            	inFile.readFromFile(reportFile, cs);
            	m_process = new CreateReceivablesPayments(m_lbSettings, m_ctx, targetBa, unknownPayerId);
            	m_process.setBgFile(inFile);
            	runCreateReceivablePayments();

            } catch (Exception e) {
            	JOptionPane.showMessageDialog(m_frame, e.getMessage(), reportFile.getName(), JOptionPane.ERROR_MESSAGE);
            	e.printStackTrace();
            }
        }

    }
	
	private void runCreateReceivablePayments() throws Exception {
		
        m_frame.getVLBParent().getFrame().setBusy(true);
        m_frame.getVLBParent().getFrame().setEnabled(false);
        m_frame.getVLBParent().getFrame().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        org.compiere.apps.SwingWorker worker = new org.compiere.apps.SwingWorker() {
        	// If construct() lets an exception escape, SwingWorker never runs
        	// finished() and the window stays locked in busy state, so catch
        	// everything and report it from finished() instead.
        	private Exception m_error;
            @Override
            public Object construct() {
            	try {
            		m_process.run();
            		return (Boolean.TRUE);
            	} catch (Exception e) {
            		m_error = e;
            		e.printStackTrace();
            		return (Boolean.FALSE);
            	}
            }
            @Override
            public void finished() {

                if (m_error!=null) {
                	m_frame.doneRunning();
                	String fileName = m_process.getFile()!=null ? m_process.getFile().getName() : "??";
                	Throwable cause = m_error.getCause()!=null ? m_error.getCause() : m_error;
                	JOptionPane.showMessageDialog(m_frame,
                			"Reading of file " + fileName + " failed:\n\n" + cause.getMessage() +
                			"\n\nSee the error log for details.",
                			fileName, JOptionPane.ERROR_MESSAGE);
                	return;
                }

                // Save file info and move file to archive unless dryRun
            	if (!m_process.isDryRun()) {
            		
                	try {
    	            	// Save file info
    	            	MLBFile fileInfo = new MLBFile(Env.getCtx(), 0, null);
    	            	fileInfo.setFileName(m_process.getFile().getName());
    	            	fileInfo.setfilepath(m_process.getFile().getParent());
    	            	fileInfo.setXC_LBPlugin_ID(m_plugin.getXC_LBPlugin_ID());
    	            	fileInfo.saveEx(null);
                	} catch (Exception ee) {
                		ee.printStackTrace();
                	}
            		
	                MLBSettings archiveDirSetting = m_lbSettings.get(MLBSettings.BG_RECEIVABLES_MOVETO_DIR);
	                if (m_process.getFile()!=null && archiveDirSetting!=null && archiveDirSetting.getName()!=null && archiveDirSetting.getName().trim().length()>0) {
	                    File srcDir = new File(archiveDirSetting.getName());
	                    m_process.getFile().renameTo(new File(srcDir, m_process.getFile().getName()));
	                }
            	}
               
            	m_frame.doneRunning();
            	
            	boolean showMessage = false;
                String fileName = m_process.getFile()!=null ? m_process.getFile().getName() : "??";
                StringBuffer doneMessage = new StringBuffer();
                doneMessage.append("File " + fileName + " successfully read.\n");
                if (m_process.isDryRun()) {
                	doneMessage.append("This was a dry run! Test mode!\n");
                }
                if (m_process.getNotProcessedPayments().size()>0) {
                	doneMessage.append(m_process.getNotProcessedPayments().size() + " payments where not matched.\n");
                	showMessage = true;
                }
    			MLBSettings autoSavePdf = m_lbSettings.get(MLBSettings.PDF_AUTO_SAVE);
    			if (autoSavePdf==null || "false".equalsIgnoreCase(autoSavePdf.getName())) {

	                doneMessage.append("Do you want to see a report of the payments?");
	                int result = JOptionPane.showConfirmDialog(null, doneMessage.toString(),
	                									"File " + fileName + " successfully read",
	                									JOptionPane.YES_NO_OPTION,
	                									JOptionPane.QUESTION_MESSAGE);
	
	                if (result==JOptionPane.YES_OPTION) {
	                	showReceivablesPaymentReport(m_process);
	                }
    			} else {
    				if (showMessage) {
    					JOptionPane.showMessageDialog(null, doneMessage.toString(), fileName, JOptionPane.WARNING_MESSAGE);
    				}
    				showReceivablesPaymentReport(m_process);
    			}
            }
        };
        worker.start();
		
	}

    /**
     * Shows a report with payments
     * 
     * @param process
     */
    public void showReceivablesPaymentReport(CreateReceivablesPayments process) {
    
    	SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmm");
    	List<PaymentExtendedRecord> payments = process.getPayments();
    	BgFile bgFile = process.getBgFile();
    	List<BgSet> bgSets = bgFile.getRecords();
    	BgSet firstSet = bgSets.get(0);
    	String recipientBankAccount = process.getBankAccount().getName();
    	String recipientBg = firstSet.getRecipientBankAccount();
    	String currency = firstSet.getCurrency();
    	java.util.Date fileDate = bgFile.getFileDate();
    	
		//Preparing parameters
		Map<String, Object> parameters = new HashMap<String, Object>();
		parameters.put("ReportTitle", "Payment report");
		parameters.put("recipientBankAccount", recipientBankAccount);
		parameters.put("currency", currency);
		parameters.put("fileDate", fileDate);
		parameters.put("recipientBg", recipientBg);

		try {
			InputStream is = getPaymentReportTemplate();
			JasperPrint print = JasperFillManager.fillReport(is, parameters, new JRBeanCollectionDataSource(payments));
			MLBSettings autoSavePdf = m_lbSettings.get(MLBSettings.PDF_AUTO_SAVE);
			if (autoSavePdf!=null && "true".equalsIgnoreCase(autoSavePdf.getName())) {
				MLBSettings pdfReportDir = m_lbSettings.get(MLBSettings.PDF_REPORT_PATH);
				// Check if sub directory "BGMax" exists
				String path = "";
				if (pdfReportDir!=null) {
					path = pdfReportDir.getName();
				}
				File reportDir = new File(path + File.separator + "BGMax");
				if (!reportDir.exists()) {
					boolean result = reportDir.mkdirs();
					if (!result) {
						JOptionPane.showMessageDialog(m_frame, "Could not create report directory : " + reportDir.getAbsolutePath());
						return;
					}
				}
				String destFileName = reportDir + File.separator + "BG" + dateFormat.format(fileDate) + ".pdf";
				net.sf.jasperreports.engine.JasperExportManager.exportReportToPdfFile(print, destFileName);
			} else {
				org.compiere.report.JasperViewer.viewReport(print);
			}
		} catch (Exception jre) {
			JOptionPane.showMessageDialog(null, jre.getMessage());
			jre.printStackTrace();
		}
    	
    }

	@Override
	public I_LBSettingsPanel getPanel() {
		
		I_LBSettingsPanel result = new BgPanel();
		return(result);
		
	}

	@Override
	public String getPluginType() {
		return(TYPE_RECEIVABLE);
	}

	@Override
	public InputStream getPaymentReportTemplate() {
		MLBSettings reportPath = m_lbSettings.get(MLBSettings.BG_PAYMENT_REPORT);
		InputStream is = null;
		if (reportPath==null || reportPath.getName()==null || reportPath.getName().trim().length()==0) {
			// Use default
			is = BgPaymentPlugin.class.getClassLoader().getResourceAsStream("org/notima/bankgiro/adempiere/ARPaymentReport.jasper");
		} else {
			try {
				is = new FileInputStream(reportPath.getName());
			} catch (FileNotFoundException e) {
				e.printStackTrace();
			}
		}
		return is;
	}

	@Override
	protected PaymentFactory createPaymentFactory() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Action[] getReceivableActions() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public Action[] getPayableActions() {
		// TODO Auto-generated method stub
		return null;
	}
	
	
}
