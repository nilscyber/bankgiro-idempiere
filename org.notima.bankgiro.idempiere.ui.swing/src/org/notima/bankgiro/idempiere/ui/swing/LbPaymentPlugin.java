package org.notima.bankgiro.idempiere.ui.swing;

import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Collection;
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
import org.notima.bankgiro.adempiere.CreateLbPayments;
import org.notima.bankgiro.adempiere.LbFileFactory;
import org.notima.bankgiro.adempiere.LbPaymentRow;
import org.notima.bankgiro.adempiere.MessageCenter;
import org.notima.bankgiro.adempiere.PaymentExtendedRecord;
import org.notima.bankgiro.adempiere.PaymentFactory;
import org.notima.bankgiro.adempiere.PaymentFileFactory;
import org.notima.bankgiro.adempiere.PaymentFileWorker;
import org.notima.bankgiro.adempiere.PluginRegistry;
import org.notima.bankgiro.adempiere.XlsFileFactory;
import org.notima.bankgiro.adempiere.form.I_LBSettingsPanel;
import org.notima.bankgiro.adempiere.form.LbPanel;
import org.notima.bankgiro.adempiere.form.VLBManagementPanel;
import org.notima.bankgiro.adempiere.model.MLBFile;
import org.notima.bankgiro.adempiere.model.MLBPlugin;
import org.notima.bankgiro.adempiere.model.MLBSettings;
import org.notima.bg.LbFile;
import org.notima.bg.lb.LbSet;

/**
 * Creates outgoing LB-payment files to send to bank. 
 * 
 * @author Daniel Tamm
 *
 */
public class LbPaymentPlugin extends PaymentPlugin {

	private Action				m_action, m_actionCreateFile;
	private MLBPlugin					m_plugin;
	
	@SuppressWarnings("serial")
	public LbPaymentPlugin(Properties ctx, VLBManagementPanel frame) throws Exception {
		super(ctx, frame);
		m_ctx = ctx;
		// Create action
		m_action = new AbstractAction() {
			public void actionPerformed(ActionEvent e) {
				createLbPayments();
			}
		};
		m_action.putValue(Action.NAME, "Read reconciliation file LB");
		m_action.putValue(Action.SHORT_DESCRIPTION, "Read reconciliation file LB-rutin");
		
		m_actionCreateFile = new AbstractAction() {
			public void actionPerformed(ActionEvent e) {
				createPaymentFile();
			}
		};
		m_actionCreateFile.putValue(Action.NAME, "Create LB payment file");
		m_actionCreateFile.putValue(Action.SHORT_DESCRIPTION, "Create LB payment file");
		
        // Read plugin information
        m_plugin = MLBPlugin.getPluginByClass(m_ctx, LbPaymentPlugin.class, null);
	}
	
	
	@Override
	public Action[] getActions() {
		return(new Action[]{ m_actionCreateFile, m_action});
	}

	@Override
	public I_LBSettingsPanel getPanel() {

		I_LBSettingsPanel result = new LbPanel();
		return(result);

	}

	private void createLbPayments() {

    	MLBSettings unknownPayer = m_lbSettings.get(MLBSettings.BG_UNKNOWN_PAYER_BPID);
    	if (unknownPayer==null) {
    		JOptionPane.showMessageDialog(m_frame, "The unknown payer BP must be set!");
    		return;
    	}
    	int unknownPayerId = new Integer(unknownPayer.getName()).intValue();
		MBankAccount targetBa = m_frame.getSelectedBankAccount();

    	// Get last directory
    	MLBSettings lastDir = m_lbSettings.get(MLBSettings.LB_RECONCILIATION_DIR);
    	JFileChooser fc;
    	if (lastDir!=null) {
    		fc = new JFileChooser(lastDir.getName());
    	} else {
    		fc = new JFileChooser();
    	}
        // Select file to open
        int retVal = fc.showDialog(m_frame, "Select reconciliation file");
        if (retVal==JFileChooser.APPROVE_OPTION) {
            File reportFile = fc.getSelectedFile();
            // Save last directory
            try {
            	String lastDirStr = fc.getSelectedFile().getParentFile().getCanonicalPath();
            	if (lastDir==null) {
            		lastDir = new MLBSettings(Env.getCtx(), 0, null);
            		lastDir.setValue(MLBSettings.LB_RECONCILIATION_DIR);
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
            LbFile inFile = new LbFile();
            Charset cs = Charset.forName("Cp850");
            try {
            	
            	inFile.readFromFile(reportFile, cs);
            	CreateLbPayments process = new CreateLbPayments(m_lbSettings, Env.getCtx(), targetBa, unknownPayerId, inFile);
            	runCreateLbPayments(process);

            } catch (Exception e) {
            	JOptionPane.showMessageDialog(m_frame, e.getMessage(), reportFile.getName(), JOptionPane.ERROR_MESSAGE);
            	e.printStackTrace();
            }
        }
        
		
	}
	
    private void runCreateLbPayments(final CreateLbPayments process) {
    	
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
            		process.run();
            		return (Boolean.TRUE);
            	} catch (Exception e) {
            		m_error = e;
            		e.printStackTrace();
            		return (Boolean.FALSE);
            	}
            }
            @Override
            public void finished() {
                m_frame.doneRunning();
                String fileName = process.getFile()!=null ? process.getFile().getName() : "??";

                if (m_error!=null) {
                	Throwable cause = m_error.getCause()!=null ? m_error.getCause() : m_error;
                	JOptionPane.showMessageDialog(m_frame,
                			"Reading of file " + fileName + " failed:\n\n" + cause.getMessage() +
                			"\n\nSee the error log for details.",
                			fileName, JOptionPane.ERROR_MESSAGE);
                	return;
                }

                if (!process.isDryRun()) {
                	
                	try {
    	            	// Save file info
    	            	MLBFile fileInfo = new MLBFile(Env.getCtx(), 0, null);
    	            	fileInfo.setFileName(process.getFile().getName());
    	            	fileInfo.setfilepath(process.getFile().getParent());
    	            	fileInfo.setXC_LBPlugin_ID(m_plugin.getXC_LBPlugin_ID());
    	            	fileInfo.saveEx(null);
                	} catch (Exception ee) {
                		ee.printStackTrace();
                	}
                	
	                // Move file to archive unless dry run
	                MLBSettings archiveDirSetting = m_lbSettings.get(MLBSettings.LB_RECONCILIATION_MOVETO_DIR);
	                if (process.getFile()!=null && archiveDirSetting!=null && archiveDirSetting.getName()!=null && archiveDirSetting.getName().trim().length()>0) {
	                    File srcDir = new File(archiveDirSetting.getName());
	                    process.getFile().renameTo(new File(srcDir, process.getFile().getName()));
	                }
                }
                
                boolean showMessage = false;
                StringBuffer doneMessage = new StringBuffer();
                doneMessage.append("File " + fileName + " successfully read.\n");
                if (process.isDryRun()) {
                	doneMessage.append("This is a dry run! Test mode!\n");
                }
                if (process.getNotProcessedPayments().size()>0) {
                	doneMessage.append(process.getNotProcessedPayments().size() + " payments where not processed.\n");
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
	                	showPaymentReport(process);
	                }
    			} else {
    				if (showMessage) {
    					JOptionPane.showMessageDialog(null, doneMessage.toString(), fileName, JOptionPane.WARNING_MESSAGE);
    				}
    				showPaymentReport(process);
    			}
                
            }
        };
        worker.start();
    }

    /**
     * Creates payment file to send to Bankgirot.
     * 
     * @return	True if creation succeeded
     */
    private void createPaymentFile() {

        // Iterate through all rows in the table, skip the ones with wrong currency
        List<LbPaymentRow> checkedRows = m_frame.getPayablesModel().getSelected();
		
        MBankAccount senderBankAccount = m_frame.getSelectedBankAccount();

        PaymentFileFactory f = PluginRegistry.registry.getFileFactory(LbFileFactory.KEY_LB_FILE);
        
        String outputDir = MLBSettings.getOutputDir();
        
        if (outputDir==null) {
        	MessageCenter.error(null, "No output dir", "No output directory defined for LB files");
        	return;
        }

        
        String result = PaymentFileWorker.createFile(f, senderBankAccount, checkedRows, new File(outputDir));

        if (result!=null)
        	MessageCenter.error(result);
        else {
        	
            MLBSettings createOutboundReport = PluginRegistry.registry.getLbSettings().get(MLBSettings.PMT_REPORT_FOR_OUT_FILE);
            boolean createReport = createOutboundReport!=null && !"false".equalsIgnoreCase(createOutboundReport.getName());
        	
        	// Create XLS report
            if (createReport) {
	        	PaymentFileFactory xlsf = PluginRegistry.registry.getFileFactory(XlsFileFactory.KEY_XLS_FILE);
	        	String result2 = PaymentFileWorker.createFile(xlsf, senderBankAccount, checkedRows, new File(outputDir));
	        	if (result2!=null)
	        		MessageCenter.error(result2);
            }
        	
        }

        // Refresh
        m_frame.runRefresh();
        
    }
    
    /**
     * Shows a report with payments
     * 
     * @param process
     */
    public void showPaymentReport(CreateLbPayments process) {
    
    	SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmm");
    	Collection<PaymentExtendedRecord> payments = process.getPayments();
    	String senderBankAccount = process.getBankAccount().getName();
    	LbFile lbFile = process.getLbFile();
    	// Get the first set
    	LbSet firstSet = (LbSet)lbFile.getRecords().get(0);
		//Preparing parameters
		Map<String, Object> parameters = new HashMap<String, Object>();
		parameters.put("ReportTitle", "Payment report");
		parameters.put("senderBankAccount", senderBankAccount);
		parameters.put("currency", firstSet.getCurrency());
		parameters.put("fileDate", lbFile.getFileDate());
		java.util.Date fileDate = lbFile.getFileDate();

		try {
			InputStream is = getPaymentReportTemplate();
			JasperPrint print = JasperFillManager.fillReport(is, parameters, new JRBeanCollectionDataSource(payments));
			MLBSettings autoSavePdf = m_lbSettings.get(MLBSettings.PDF_AUTO_SAVE);
			if (autoSavePdf!=null && "true".equalsIgnoreCase(autoSavePdf.getName())) {
				MLBSettings pdfReportDir = m_lbSettings.get(MLBSettings.PDF_REPORT_PATH);
				// Check if sub directory "LB" exists
				String path = "";
				if (pdfReportDir!=null) {
					path = pdfReportDir.getName();
				}
				File reportDir = new File(path + File.separator + "LB");
				if (!reportDir.exists()) {
					boolean result = reportDir.mkdirs();
					if (!result) {
						JOptionPane.showMessageDialog(m_frame, "Could not create report directory : " + reportDir.getAbsolutePath());
						return;
					}
				}
				String destFileName = reportDir + File.separator + "LB" + dateFormat.format(fileDate) + ".pdf";
				net.sf.jasperreports.engine.JasperExportManager.exportReportToPdfFile(print, destFileName);
			} else {
				org.compiere.report.JasperViewer.viewReport(print);
			}
			
		} catch (Exception jre) {
			MessageCenter.error(jre.getMessage());
			jre.printStackTrace();
		}
    	
    }
    
	@Override
	public String getPluginType() {
		return(TYPE_PAYABLE);
	}

	@Override
	public InputStream getPaymentReportTemplate() {
		MLBSettings reportPath = m_lbSettings.get(MLBSettings.LB_PAYMENT_REPORT);
		InputStream is = null;
		if (reportPath==null) {
			// Use default
			is = LbPaymentPlugin.class.getClassLoader().getResourceAsStream("org/notima/bankgiro/adempiere/LbPaymentReport.jasper");
		} else {
			try {
				is = new FileInputStream(reportPath.getName());
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		if (is==null) {
			JOptionPane.showMessageDialog(m_frame, "No report found / report path not set. Can't display report");
			return null;
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
