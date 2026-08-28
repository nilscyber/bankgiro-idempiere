package org.notima.idempiere.iso20022;

import java.io.InputStream;
import java.util.Properties;

import org.notima.bankgiro.adempiere.HeadlessPaymentPlugin;
import org.notima.bankgiro.adempiere.PaymentFactory;
import org.notima.bankgiro.adempiere.PluginRegistry;

public class Iso20022HeadlessPlugin extends HeadlessPaymentPlugin {

	public Iso20022HeadlessPlugin(Properties ctx) throws Exception {
		super(ctx);
	}

	@Override
	protected PaymentFactory createPaymentFactory() {
		return new Iso20022PaymentFactory(PluginRegistry.registry.getLbSettings(), m_ctx);
	}

	@Override
	public InputStream getPaymentReportTemplate() {
		return Iso20022HeadlessPlugin.class.getClassLoader().getResourceAsStream("org/notima/idempiere/iso20022/DonePaymentsReport.jasper");
	}

	@Override
	protected boolean isHeadless() {
		return true;
	}

}
