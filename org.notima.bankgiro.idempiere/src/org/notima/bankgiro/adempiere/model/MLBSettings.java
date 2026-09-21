/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.notima.bankgiro.adempiere.model;

import java.sql.ResultSet;
import java.util.Calendar;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.SortedMap;

import org.compiere.model.Query;
import org.compiere.util.CPreparedStatement;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Ini;

/**
 *
 * @author Daniel Tamm
 */
public class MLBSettings extends X_XC_LBSettings {

	// Directory where LB files are output
    public static final String LB_OUTPUT_DIR = "LB_OUTPUT_DIR";
    // Directory where read reconciliation files are archived
    public static final String LB_RECONCILIATION_MOVETO_DIR = "LB_RECON_MDIR";
    // Directory where last reconciliation file was read
    public static final String LB_RECONCILIATION_DIR = "LB_RECONCILE_DIR";
    // Name of BG file
    public static final String LB_FILE_NAME = "LB_FILE_NAME";
    // Suffix of BG file
    public static final String LB_FILE_SUFFIX = "LB_FILE_SUFFIX";
    // Add timestamp to file name
    public static final String LB_ADD_TS = "LB_ADD_TS";
    // UTL bank file format
    public static final String LB_UTL_FMT = "LB_UTL_FMT";
    // Directory where last receivables file was read
    public static final String BG_RECEIVABLES_DIR = "BG_RECEIVE_DIR";
    // Directory where read receivables files are archived
    public static final String BG_RECEIVABLES_MOVETO_DIR = "BG_RECE_MDIR";
    // BPartner used for unknown payments
    public static final String BG_UNKNOWN_PAYER_BPID = "BG_UNKNOWN_PAYER";
    // Path to JasperReport for payment report
    public static final String BG_PAYMENT_REPORT = "BG_PAYMENT_RPT";
    // Path to JasperReport for lb payment report
    public static final String LB_PAYMENT_REPORT = "LB_PAYMENT_RPT";
    // File charset for receivables file
    public static final String BG_FILE_CODEPAGE = "BG_FILE_CODEPAGE";
    // File charset for LB files
    public static final String LB_FILE_CODEPAGE = "LB_FILE_CODEPAGE";
    // Path for PDF-reports
    public static final String PDF_REPORT_PATH = "PDF_REPORT_PATH";
    // Dry run flag, if true no payments are created processed
    public static final String BG_DRYRUN_FLAG = "BG_DRYRUN_FLAG";
    // Auto save flag
    public static final String PDF_AUTO_SAVE = "PDF_AUTO_SAVE";
    // Auto complete flag
    public static final String PMT_AUTO_COMPLETE = "PMT_AUTO_CMPL";
    // Amount threshold
    public static final String AMT_THRESHOLD = "AMT_THRESHOLD";
    // Default if reference to payee is empty
    public static final String LB_EMTPY_REF = "LB_EMPTY_REF";
    // Create payment report (outgoing files)
    public static final String PMT_REPORT_FOR_OUT_FILE = "PMT_RPT_OUT";

    /** Standard Constructor */
    public MLBSettings (Properties ctx, int XC_LBSettings_ID, String trxName)
    {
      super (ctx, XC_LBSettings_ID, trxName);
      if (XC_LBSettings_ID == 0)
        {
			setAD_User_ID(Env.getAD_User_ID(ctx));
            set_ValueOfColumn("AD_Client_ID", Env.getAD_Client_ID(ctx));
            setAD_Org_ID(Env.getAD_Org_ID(ctx));
        }
    }

    /** Load Constructor */
    public MLBSettings (Properties ctx, ResultSet rs, String trxName)
    {
      super (ctx, rs, trxName);
    }

    /**
     * Return a sorted map of settings for this user
     *
     * @param ctx
     * @param AD_User_ID
     * @return
     */
    private static void putSettingsForUser(Properties ctx, int AD_User_ID, int AD_Org_ID, SortedMap<String, MLBSettings> result) {
        Query q = new Query(ctx, MLBSettings.Table_Name, "(AD_User_ID=? OR AD_User_ID=0) and (AD_Org_ID=0 or AD_Org_ID=?)", null);
        q.setParameters(new Object[]{AD_User_ID, AD_Org_ID});
        List<X_XC_LBSettings> list = q.list();
        MLBSettings setting;
        X_XC_LBSettings s;
        result.clear();
        for (Iterator<X_XC_LBSettings> it = list.iterator(); it.hasNext();) {
            s = it.next();
            setting = new MLBSettings(ctx, s.get_ID(), null);
            if (Ini.isClient() && result.get(setting.getValue())!=null && setting.getAD_User_ID()==0) {
            	// Global setting, but we already have a user specified setting. User setting 
            	// takes precedence.
            	continue;
            }
            result.put(setting.getValue(), setting);
        }
    }

    /**
     * The Value column is 20 characters. A longer key is silently truncated
     * by the generated setter, so the setting can be saved but never found
     * again (bit us with ISO20022_BATCH_BOOKING, 22 chars, 2026-09-21).
     */
    private static void assertKeyFits(String key) {
    	if (key!=null && key.length()>20) {
    		throw new IllegalArgumentException("LB setting key '" + key + "' is longer than 20 characters and would be truncated (XC_LBSettings.Value)");
    	}
    }

    /**
     * Sets a setting for the current user.
     */
    public static MLBSettings setSetting(String key, String name) {
    	try {
    		assertKeyFits(key);
	        Properties ctx = Env.getCtx();
	        int AD_User_ID = Env.getAD_User_ID(ctx);
	        int AD_Org_ID = Env.getAD_Org_ID(ctx);
	        CPreparedStatement ps = DB.prepareStatement("delete from " + MLBSettings.Table_Name + " WHERE AD_User_ID=? and Value=? and (AD_Org_ID=0 OR AD_Org_ID=?)", null);
	        ps.setInt(1, AD_User_ID);
	        ps.setString(2, key);
	        ps.setInt(3, AD_Org_ID);
	        ps.executeUpdate();
	        ps.close();
	        X_XC_LBSettings setting = new X_XC_LBSettings(ctx, 0, null);
	        setting.setValue(key);
	        setting.setName(name);
	        setting.setAD_User_ID(AD_User_ID);
	        setting.setAD_Org_ID(AD_Org_ID);
	        setting.saveEx();
	        MLBSettings result = new MLBSettings(ctx, setting.get_ID(), null);
	        return result;
    	} catch (Exception ee) {
    		System.err.println("Could not save setting " + key + " : " + name + " : " + ee.getMessage());
    		ee.printStackTrace();
    		return null;
    	}
    }
    
    /**
     * Sets a setting for the current system (ad_user_id=0).
     */
    public static MLBSettings setSystemSetting(String key, String name) {
    	try {
    		assertKeyFits(key);
	        Properties ctx = Env.getCtx();
	        int AD_Org_ID = Env.getAD_Org_ID(ctx);
	        CPreparedStatement ps = DB.prepareStatement("delete from " + MLBSettings.Table_Name + " WHERE (AD_User_ID=0 OR AD_User_ID is NULL) and Value=? and (AD_Org_ID=0 OR AD_Org_ID=?)", null);
	        ps.setString(1, key);
	        ps.setInt(2, AD_Org_ID);
	        ps.executeUpdate();
	        ps.close();
	        MLBSettings setting = new MLBSettings(ctx, 0, null);
	        setting.setValue(key);
	        setting.setName(name);
	        setting.set_ValueOfColumn(MLBSettings.COLUMNNAME_AD_User_ID, new Integer(0)); // System wide indicator
	        setting.saveEx();
	        MLBSettings result = new MLBSettings(ctx, setting.get_ID(), null);
	        return result;
    	} catch (Exception ee) {
    		System.err.println("Could not save setting " + key + " : " + name + " : " + ee.getMessage());
    		ee.printStackTrace();
    		return null;
    	}
    }
    

    /**
     * Fills a sorted map of settings for current user
     */
    public static void putSettings(SortedMap<String, MLBSettings> settings) {
        Properties ctx = Env.getCtx();
        int AD_User_ID = Ini.isClient() ? Env.getAD_User_ID(ctx) : 0;
        int AD_Org_ID = Env.getAD_Org_ID(ctx);
        putSettingsForUser(ctx, AD_User_ID, AD_Org_ID, settings);
    }
    
    /**
     * Fills a sorted map of settings for system (AD_User_ID = 0)
     * @param settings
     */
    public static void putSettings(SortedMap<String, MLBSettings> settings, int AD_User_ID, int AD_Org_ID) {
    	Properties ctx = Env.getCtx();
    	putSettingsForUser(ctx, AD_User_ID, AD_Org_ID, settings);
    }
    
    /**
     * Return output directory of current user.
     * @return
     */
    public static String getOutputDir() {
        Properties ctx = Env.getCtx();
        int AD_User_ID = Env.getAD_User_ID(ctx);
        int AD_Org_ID = Env.getAD_Org_ID(ctx);
        Query q = new Query(ctx, MLBSettings.Table_Name, "AD_User_ID=? and (AD_Org_ID=0 or AD_Org_ID=?) and value=?", null);
        q.setParameters(new Object[]{AD_User_ID, AD_Org_ID, MLBSettings.LB_OUTPUT_DIR});
        X_XC_LBSettings s = q.first();
        if (s==null) return null;
        MLBSettings setting = new MLBSettings(s.getCtx(), s.get_ID(), null);
        return(setting.getName());
    }

    /**
     * Returns the file name based on the settings
     * @return
     */
    public static String getFileName(SortedMap<String, MLBSettings> settings) {
        MLBSettings fileName = settings.get(MLBSettings.LB_FILE_NAME);
        String fileNameText = fileName!=null ? fileName.getName() : "BGDATA";
        MLBSettings addTime = settings.get(MLBSettings.LB_ADD_TS);
        if (addTime!=null && "Y".equalsIgnoreCase(addTime.getName())) {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyMMdd.HHmm");
            java.util.Date now = Calendar.getInstance().getTime();
            fileNameText += format.format(now);
        }
        MLBSettings fileSuffix = settings.get(MLBSettings.LB_FILE_SUFFIX);
        fileNameText += "." + (fileSuffix!=null ? fileSuffix.getName() : "IN");
        return(fileNameText);
    }

}
