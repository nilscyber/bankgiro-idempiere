/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.notima.idempiere.iso20022;

/**
 * Class containing constants.
 *
 * @author daniel.tamm
 */
public class Iso20022Settings {

    public static final String ISO20022_OUTPUT_DIR = "ISO2_OUTPUT_DIR";
    public static final String ISO20022_FILE_NAME = "ISO2_FILE_NAME";
    public static final String ISO20022_ADD_TS = "ISO2_ADD_TS";
    public static final String ISO20022_FILE_SUFFIX = "ISO2_FILE_SUFFIX";
    public static final String ISO20022_RECONCILIATION_DIR = "ISO2_RECONCILE_DIR";
    public static final String ISO20022_RECONCILIATION_MOVETO_DIR = "ISO2_RECON_MDIR";

    // On the idempiere-3.1 branch this constant lives in Iso20022FileFactory,
    // which is not ported here (outbound PAIN is not used on this branch).
    public static final String ISO20022_MSGPREFIX = "ISO20022_MSGPREFIX";

}
