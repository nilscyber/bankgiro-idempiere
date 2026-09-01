package org.notima.idempiere.iso20022.ui.swing;

import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.filechooser.FileFilter;

import org.compiere.model.MBankAccount;
import org.compiere.util.Env;
import org.notima.bankgiro.adempiere.LbPaymentRow;
import org.notima.bankgiro.adempiere.MessageCenter;
import org.notima.bankgiro.adempiere.PaymentExtendedRecord;
import org.notima.bankgiro.adempiere.PaymentFactory;
import org.notima.bankgiro.adempiere.PaymentFileFactory;
import org.notima.bankgiro.adempiere.PaymentFileWorker;
import org.notima.bankgiro.adempiere.PluginRegistry;
import org.notima.idempiere.iso20022.Iso20022FileFactory;
import org.notima.bankgiro.adempiere.form.I_LBSettingsPanel;
import org.notima.bankgiro.adempiere.form.VLBManagementPanel;
import org.notima.bankgiro.adempiere.model.MLBFile;
import org.notima.bankgiro.adempiere.model.MLBSettings;
import org.notima.bankgiro.idempiere.ui.swing.PaymentPlugin;
import org.notima.idempiere.iso20022.Iso20022HeadlessPlugin;
import org.notima.idempiere.iso20022.Iso20022PaymentFactory;
import org.notima.idempiere.iso20022.Iso20022Settings;

import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.data.JRBeanCollectionDataSource;

/**
 * Plugin that reads ISO20022 CAMT-54 reconciliation files (incoming payments).
 *
 * Ported from the idempiere-3.1 branch. The outbound PAIN file creation that
 * exists on that branch is not ported, and the iDempiere OSGi report viewer
 * lookup is replaced with org.compiere.report.JasperViewer.
 */
public class Iso20022PaymentPlugin extends PaymentPlugin {

	private Action			m_actionCreateFile;
	private Action			m_actionReadFile;
	private Action			m_actionReadReceivables;

	private I_LBSettingsPanel	m_panel;

	public Iso20022PaymentPlugin(Properties ctx, VLBManagementPanel frame)
			throws Exception {
		super(ctx, frame);

		m_actionCreateFile = new AbstractAction() {

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				createFile();
			}

		};
		m_actionCreateFile.putValue(Action.NAME, "Create ISO20022 File");

		m_actionReadFile = new AbstractAction() {

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				createPaymentsFromReconciliationFile(false);
			}

		};
		m_actionReadFile.putValue(Action.NAME, "Read reconciliation file ISO20022");
		m_actionReadFile.putValue(Action.SHORT_DESCRIPTION, "Read reconciliation file ISO20022");

		m_actionReadReceivables = new AbstractAction() {

			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(ActionEvent e) {
				createPaymentsFromReconciliationFile(true);
			}

		};
		m_actionReadReceivables.putValue(Action.NAME, "Read receivables file ISO20022");
		m_actionReadReceivables.putValue(Action.SHORT_DESCRIPTION, "Read receivables file ISO20022");

	}

	/**
	 * Creates outgoing payment file (PAIN format). Called from m_actionCreateFile.
	 */
	private void createFile() {

        // Iterate through all rows in the table, skip the ones with wrong currency
        List<LbPaymentRow> checkedRows = m_frame.getPayablesModel().getSelected();

        MBankAccount senderBankAccount = m_frame.getSelectedBankAccount();

        PaymentFileFactory f = PluginRegistry.registry.getFileFactory(Iso20022FileFactory.KEY);

        if (f==null) {
        	f = new Iso20022FileFactory();
        	PluginRegistry.registry.addPaymentFileFactory(f);
        }

        MLBSettings oDir = PluginRegistry.registry.getLbSettings().get(Iso20022Settings.ISO20022_OUTPUT_DIR);
        String outputDir =  oDir!=null ? oDir.getName() : null;

        if (outputDir==null) {
        	MessageCenter.error(null, "No output dir", "No output directory defined for ISO20022-files");
        	return;
        }

        String result = PaymentFileWorker.createFile(f, senderBankAccount, checkedRows, new File(outputDir));

        if (result!=null)
        	MessageCenter.error(result);

        // Refresh
        m_frame.runRefresh();

	}

	/**
	 * Creates payments from reconciliation file
	 */
	private void createPaymentsFromReconciliationFile(boolean receivablesOnly) {

    	MLBSettings unknownPayer = PluginRegistry.registry.getLbSettings().get(MLBSettings.BG_UNKNOWN_PAYER_BPID);
    	if (unknownPayer==null) {
    		JOptionPane.showMessageDialog(m_frame, "The unknown payer BP must be set!");
    		return;
    	}

    	// Get last directory. Ignore empty settings (the settings panel saves
    	// blank fields too) so the chooser falls back to the default location.
    	MLBSettings lastDir = PluginRegistry.registry.getLbSettings().get(Iso20022Settings.ISO20022_RECONCILIATION_DIR);
    	JFileChooser fc;
    	if (lastDir!=null && lastDir.getName()!=null && lastDir.getName().trim().length()>0) {
    		fc = new JFileChooser(lastDir.getName());
    	} else {
    		fc = new JFileChooser();
    	}
        // Select file to open

    	if (receivablesOnly) {
	    	fc.setFileFilter(new FileFilter() {

				@Override
				public boolean accept(File f) {
					return (f.getName().toLowerCase().contains("kred"));
				}

				@Override
				public String getDescription() {
					return "Inbetalningar";
				}});
    	}

        int retVal = fc.showDialog(m_frame, "Select file");
        if (retVal==JFileChooser.APPROVE_OPTION) {
            File reportFile = fc.getSelectedFile();
            // Save last directory as a per-user setting, and refresh the
            // cached settings map so the same session sees the new value.
            try {
            	String lastDirStr = fc.getSelectedFile().getParentFile().getCanonicalPath();
            	MLBSettings updated = MLBSettings.setSetting(Iso20022Settings.ISO20022_RECONCILIATION_DIR, lastDirStr);
            	if (updated!=null) {
            		PluginRegistry.registry.getLbSettings().put(Iso20022Settings.ISO20022_RECONCILIATION_DIR, updated);
            	}
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

            try {

            	Iso20022PaymentFactory process = new Iso20022PaymentFactory(m_lbSettings, Env.getCtx());
            	if (receivablesOnly)
            		process.setProperty(Iso20022PaymentFactory.PROP_RECEIVABLES_ONLY, "Y");
            	process.setFile(reportFile);
            	runCreateIsoPayments(process);

            } catch (Exception e) {
            	JOptionPane.showMessageDialog(m_frame, e.getMessage(), reportFile.getName(), JOptionPane.ERROR_MESSAGE);
            	e.printStackTrace();
            }
        }

	}

    private void runCreateIsoPayments(final Iso20022PaymentFactory process) {

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
	                MLBSettings archiveDirSetting = PluginRegistry.registry.getLbSettings().get(Iso20022Settings.ISO20022_RECONCILIATION_MOVETO_DIR);
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
                if (process.getSkippedExistingCount()>0) {
                	doneMessage.append(process.getSkippedExistingCount() + " Swish transactions skipped (payment already exists).\n");
                }
    			MLBSettings autoSavePdf = PluginRegistry.registry.getLbSettings().get(MLBSettings.PDF_AUTO_SAVE);
    			if (autoSavePdf==null || "false".equalsIgnoreCase(autoSavePdf.getName())) {

	                doneMessage.append("Do you want to see a report of the payments?");
	                int result = JOptionPane.showConfirmDialog(null, doneMessage.toString(),
	                									"File " + fileName + " successfully read",
	                									JOptionPane.YES_NO_OPTION,
	                									JOptionPane.QUESTION_MESSAGE);

	                if (result==JOptionPane.YES_OPTION) {
	                	showPaymentReport(process, true);
	                }
    			} else {
    				if (showMessage) {
    					JOptionPane.showMessageDialog(null, doneMessage.toString(), fileName, JOptionPane.WARNING_MESSAGE);
    				}
    				showPaymentReport(process, false);
    			}

            }
        };
        worker.start();
    }

    /**
     * Shows a report with payments
     *
     * @param payments
     */
    private void showPaymentReport(Iso20022PaymentFactory process, boolean showInPdfReaderAfterSave) {

    	Collection<PaymentExtendedRecord> payments = process.getPayments();

    	// Make sure that non processed payments are last
    	List<PaymentExtendedRecord> paymentList = new ArrayList<PaymentExtendedRecord>();
    	for (PaymentExtendedRecord p : payments) {
    		if (!p.isProcessed()) {
    			paymentList.add(p);
    		} else {
    			paymentList.add(0, p);
    		}
    	}

    	String recipientBankAccount = process.getBankAccount().getName();

		Map<String, Object> parameters = new HashMap<String, Object>();
		parameters.put("ReportTitle", "Payment report");
		parameters.put("senderBankAccount", recipientBankAccount);
		parameters.put("fileDate", process.getTrxDate());
		parameters.put("currency", process.getBankAccount().getC_Currency().getISO_Code());
		String fileName = process.getFile().getName();

		parameters.put("fileName", fileName);

		try {

			InputStream is = null;
			// Use default
			is = getPaymentReportTemplate();
			if (is==null) {
				JOptionPane.showMessageDialog(m_frame, "No report found / report path not set. Can't display report");
				return;
			}

			JasperPrint print = JasperFillManager.fillReport(is, parameters, new JRBeanCollectionDataSource(paymentList));
			MLBSettings autoSavePdf = m_lbSettings.get(MLBSettings.PDF_AUTO_SAVE);
			if (autoSavePdf!=null && "true".equalsIgnoreCase(autoSavePdf.getName())) {
				MLBSettings pdfReportDir = m_lbSettings.get(MLBSettings.PDF_REPORT_PATH);
				// Check if sub directory "Iso20022" exists
				String path = "";
				if (pdfReportDir!=null) {
					path = pdfReportDir.getName();
				}
				File reportDir = new File(path + File.separator + "Iso20022");
				if (!reportDir.exists()) {
					boolean result = reportDir.mkdirs();
					if (!result) {
						JOptionPane.showMessageDialog(m_frame, "Could not create report directory : " + reportDir.getAbsolutePath());
						return;
					}
				}
				String destFileName = reportDir + File.separator + fileName + ".pdf";

				net.sf.jasperreports.engine.JasperExportManager.exportReportToPdfFile(print, destFileName);

				if (showInPdfReaderAfterSave) {

					openFileWithSystemProgram(destFileName);

				}

			} else {
				org.compiere.report.JasperViewer.viewReport(print);
			}

		} catch (Exception jre) {
			JOptionPane.showMessageDialog(null, jre.getMessage());
			jre.printStackTrace();
		}

    }


	@Override
	public Action[] getActions() {
		return new Action[]{m_actionCreateFile, m_actionReadFile};
	}

	@Override
	public Action[] getReceivableActions() {
		return new Action[]{m_actionReadReceivables};
	}

	@Override
	public Action[] getPayableActions() {
		return new Action[]{m_actionCreateFile, m_actionReadFile};
	}

	@Override
	public I_LBSettingsPanel getPanel() {
		if (m_panel==null)
			m_panel = new Iso20022Panel();

		return m_panel;
	}

	@Override
	public String getPluginType() {
		return TYPE_BOTH;
	}

	@Override
	protected PaymentFactory createPaymentFactory() {
		return new Iso20022PaymentFactory(PluginRegistry.registry.getLbSettings(), Env.getCtx());
	}

	@Override
	public InputStream getPaymentReportTemplate() {
		return Iso20022HeadlessPlugin.class.getClassLoader().getResourceAsStream("org/notima/idempiere/iso20022/DonePaymentsReport.jasper");
	}

}
