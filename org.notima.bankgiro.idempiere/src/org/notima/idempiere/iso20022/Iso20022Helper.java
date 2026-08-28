package org.notima.idempiere.iso20022;

import java.util.List;

/**
 * Helper methods for ISO20022 files.
 *
 * NOTE: This is a subset of the Iso20022Helper on the idempiere-3.1 branch.
 * The outbound (PAIN) helper methods are not ported since outbound ISO20022
 * payments are not used on this branch.
 *
 * @author Daniel Tamm
 */
public class Iso20022Helper {

	/**
	 * Tries to extract the most likely reference.
	 *
	 * TODO: Make it more robust and handle more cases
	 *
	 * @param stri
	 * @return
	 */
	public static String getMostLikelyReference(iso.std.iso._20022.tech.xsd.camt_054_001.StructuredRemittanceInformation7 stri) {

		if (stri==null) return null;

		iso.std.iso._20022.tech.xsd.camt_054_001.CreditorReferenceInformation2 cri = stri.getCdtrRefInf();

		if (cri!=null) {
			if (cri.getRef()!=null)
				return cri.getRef();
		}

		List<iso.std.iso._20022.tech.xsd.camt_054_001.ReferredDocumentInformation3> rlist = stri.getRfrdDocInf();
		if (rlist!=null && rlist.size()>0) {
			iso.std.iso._20022.tech.xsd.camt_054_001.ReferredDocumentInformation3 rdi = rlist.get(0);
			if (rdi.getNb()!=null)
				return rdi.getNb();
		}

		return "";
	}

}
