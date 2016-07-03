/*
 * $Id$
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ironrhino.core.struts.result;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.util.Map;
import java.util.TimeZone;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.StringUtils;
import org.apache.struts2.StrutsStatics;
import org.apache.struts2.dispatcher.StrutsResultSupport;
import org.apache.struts2.views.jasperreports.JasperReportConstants;
import org.apache.struts2.views.jasperreports.ValueStackDataSource;
import org.apache.struts2.views.jasperreports.ValueStackShadowMap;

import com.opensymphony.xwork2.ActionInvocation;
import com.opensymphony.xwork2.util.ValueStack;
import com.opensymphony.xwork2.util.logging.Logger;
import com.opensymphony.xwork2.util.logging.LoggerFactory;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JRParameter;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.JasperReport;
import net.sf.jasperreports.engine.export.HtmlExporter;
import net.sf.jasperreports.engine.export.JRCsvExporter;
import net.sf.jasperreports.engine.export.JRPdfExporter;
import net.sf.jasperreports.engine.export.JRRtfExporter;
import net.sf.jasperreports.engine.export.JRXlsExporter;
import net.sf.jasperreports.engine.export.JRXmlExporter;
import net.sf.jasperreports.engine.util.JRLoader;
import net.sf.jasperreports.export.Exporter;
import net.sf.jasperreports.export.SimpleExporterInput;
import net.sf.jasperreports.export.SimpleHtmlExporterOutput;
import net.sf.jasperreports.export.SimpleOutputStreamExporterOutput;
import net.sf.jasperreports.export.SimpleWriterExporterOutput;

/**
 * <!-- START SNIPPET: description -->
 * <p/>
 * Generates a JasperReports report using the specified format or PDF if no
 * format is specified.
 * <p/>
 * <!-- END SNIPPET: description -->
 * <p />
 * <b>This result type takes the following parameters:</b>
 * <p/>
 * <!-- START SNIPPET: params -->
 * <p/>
 * <ul>
 * <p/>
 * <li><b>location (default)</b> - the location where the compiled jasper report
 * definition is (foo.jasper), relative from current URL.</li>
 * <p/>
 * <li><b>dataSource (required)</b> - the EL expression used to retrieve the
 * datasource from the value stack (usually a List).</li>
 * <p/>
 * <li><b>parse</b> - true by default. If set to false, the location param will
 * not be parsed for EL expressions.</li>
 * <p/>
 * <li><b>format</b> - the format in which the report should be generated. Valid
 * values can be found in {@link JasperReportConstants}. If no format is
 * specified, PDF will be used.</li>
 * <p/>
 * <li><b>contentDisposition</b> - disposition (defaults to "inline", values are
 * typically <i>filename="document.pdf"</i>).</li>
 * <p/>
 * <li><b>documentName</b> - name of the document (will generate the http header
 * <code>Content-disposition = X; filename=X.[format]</code>).</li>
 * <p/>
 * <li><b>delimiter</b> - the delimiter used when generating CSV reports. By
 * default, the character used is ",".</li>
 * <p/>
 * <li><b>imageServletUrl</b> - name of the url that, when prefixed with the
 * context page, can return report images.</li>
 * <p/>
 * <li><b>reportParameters</b> - (2.1.2+) OGNL expression used to retrieve a map
 * of report parameters from the value stack. The parameters may be accessed in
 * the report via the usual JR mechanism and might include data not part of the
 * dataSource, such as the user name of the report creator, etc.</li>
 * <p/>
 * <li><b>exportParameters</b> - (2.1.2+) OGNL expression used to retrieve a map
 * of JR exporter parameters from the value stack. The export parameters are
 * used to customize the JR export. For example, a PDF export might enable
 * encryption and set the user password to a string known to the report creator.
 * </li>
 * <p/>
 * <li><b>connection</b> - (2.1.7+) JDBC Connection which can be passed to the
 * report instead of dataSource</li>
 * <p/>
 * </ul>
 * <p/>
 * <p>
 * This result follows the same rules from {@link StrutsResultSupport}.
 * Specifically, all parameters will be parsed if the "parse" parameter is not
 * set to false.
 * </p>
 * <!-- END SNIPPET: params -->
 * <p/>
 * <b>Example:</b>
 * <p/>
 * 
 * <pre>
 * <!-- START SNIPPET: example1 -->
 * &lt;result name="success" type="jasper"&gt;
 *   &lt;param name="location"&gt;foo.jasper&lt;/param&gt;
 *   &lt;param name="dataSource"&gt;mySource&lt;/param&gt;
 *   &lt;param name="format"&gt;CSV&lt;/param&gt;
 * &lt;/result&gt;
 * <!-- END SNIPPET: example1 -->
 * </pre>
 * 
 * or for pdf
 * 
 * <pre>
 * <!-- START SNIPPET: example2 -->
 * &lt;result name="success" type="jasper"&gt;
 *   &lt;param name="location"&gt;foo.jasper&lt;/param&gt;
 *   &lt;param name="dataSource"&gt;mySource&lt;/param&gt;
 * &lt;/result&gt;
 * <!-- END SNIPPET: example2 -->
 * </pre>
 */
@SuppressWarnings({ "rawtypes", "unchecked" })
public class JasperReportsResult extends StrutsResultSupport implements JasperReportConstants {

	private static final long serialVersionUID = -2523174799621182907L;

	private final static Logger LOG = LoggerFactory.getLogger(JasperReportsResult.class);

	protected String dataSource;
	protected String format;
	protected String documentName;
	protected String contentDisposition;
	protected String timeZone;
	protected boolean wrapField = true;

	/**
	 * Connection which can be passed to the report instead od dataSource.
	 */
	protected String connection;

	/**
	 * Names a report parameters map stack value, allowing additional report
	 * parameters from the action.
	 */
	protected String reportParameters;

	/**
	 * Names an exporter parameters map stack value, allowing the use of custom
	 * export parameters.
	 */
	protected String exportParameters;

	/**
	 * Default ctor.
	 */
	public JasperReportsResult() {
		super();
	}

	/**
	 * Default ctor with location.
	 *
	 * @param location
	 *            Result location.
	 */
	public JasperReportsResult(String location) {
		super(location);
	}

	public void setDataSource(String dataSource) {
		this.dataSource = dataSource;
	}

	public void setFormat(String format) {
		this.format = format;
	}

	public void setDocumentName(String documentName) {
		this.documentName = documentName;
	}

	public void setContentDisposition(String contentDisposition) {
		this.contentDisposition = contentDisposition;
	}

	/**
	 * set time zone id
	 *
	 * @param timeZone
	 */
	public void setTimeZone(final String timeZone) {
		this.timeZone = timeZone;
	}

	public void setWrapField(boolean wrapField) {
		this.wrapField = wrapField;
	}

	public String getReportParameters() {
		return reportParameters;
	}

	public void setReportParameters(String reportParameters) {
		this.reportParameters = reportParameters;
	}

	public String getExportParameters() {
		return exportParameters;
	}

	public void setExportParameters(String exportParameters) {
		this.exportParameters = exportParameters;
	}

	public String getConnection() {
		return connection;
	}

	public void setConnection(String connection) {
		this.connection = connection;
	}

	@Override
	protected void doExecute(String finalLocation, ActionInvocation invocation) throws Exception {
		// Will throw a runtime exception if no "datasource" property.
		initializeProperties(invocation);

		if (LOG.isDebugEnabled()) {
			LOG.debug("Creating JasperReport for dataSource = " + dataSource + ", format = " + format, new Object[0]);
		}

		HttpServletRequest request = (HttpServletRequest) invocation.getInvocationContext()
				.get(StrutsStatics.HTTP_REQUEST);
		HttpServletResponse response = (HttpServletResponse) invocation.getInvocationContext()
				.get(StrutsStatics.HTTP_RESPONSE);

		// Handle IE special case: it sends a "contype" request first.
		if ("contype".equals(request.getHeader("User-Agent"))) {
			try {
				response.setContentType("application/pdf");
				response.setContentLength(0);

				ServletOutputStream outputStream = response.getOutputStream();
				outputStream.close();
			} catch (IOException e) {
				LOG.error("Error writing report output", e);
				throw new ServletException(e.getMessage(), e);
			}
			return;
		}

		// Construct the data source for the report.
		ValueStack stack = invocation.getStack();
		ValueStackDataSource stackDataSource = null;

		Connection conn = (Connection) stack.findValue(connection);
		if (conn == null)
			stackDataSource = new ValueStackDataSource(stack, dataSource);

		// Determine the directory that the report file is in and set the
		// reportDirectory parameter
		// For WW 2.1.7:
		// ServletContext servletContext = ((ServletConfig)
		// invocation.getInvocationContext().get(ServletActionContext.SERVLET_CONFIG)).getServletContext();
		ServletContext servletContext = (ServletContext) invocation.getInvocationContext()
				.get(StrutsStatics.SERVLET_CONTEXT);
		String systemId = servletContext.getRealPath(finalLocation);
		Map parameters = new ValueStackShadowMap(stack);
		File directory = new File(systemId.substring(0, systemId.lastIndexOf(File.separator)));
		parameters.put("reportDirectory", directory);
		parameters.put(JRParameter.REPORT_LOCALE, invocation.getInvocationContext().getLocale());

		// put timezone in jasper report parameter
		if (timeZone != null) {
			timeZone = conditionalParse(timeZone, invocation);
			final TimeZone tz = TimeZone.getTimeZone(timeZone);
			if (tz != null) {
				// put the report time zone
				parameters.put(JRParameter.REPORT_TIME_ZONE, tz);
			}
		}

		// Add any report parameters from action to param map.
		Map reportParams = (Map) stack.findValue(reportParameters);
		if (reportParams != null) {
			if (LOG.isDebugEnabled()) {
				LOG.debug("Found report parameters; adding to parameters...", new Object[0]);
			}
			parameters.putAll(reportParams);
		}

		JasperPrint jasperPrint;

		// Fill the report and produce a print object
		try {
			JasperReport jasperReport = (JasperReport) JRLoader.loadObject(new File(systemId));
			if (conn == null)
				jasperPrint = JasperFillManager.fillReport(jasperReport, parameters, stackDataSource);
			else
				jasperPrint = JasperFillManager.fillReport(jasperReport, parameters, conn);
		} catch (JRException e) {
			LOG.error("Error building report for uri " + systemId, e);
			throw new ServletException(e.getMessage(), e);
		} finally {
			if (conn != null)
				try {
					conn.close();
				} catch (Exception e) {
					LOG.warn("Could not close db connection properly", e);
				}
		}

		// Export the print object to the desired output format
		try {
			if (contentDisposition != null || documentName != null) {
				final StringBuffer tmp = new StringBuffer();
				tmp.append((contentDisposition == null) ? "inline" : contentDisposition);

				if (documentName != null) {
					tmp.append("; filename=");
					tmp.append(documentName);
					tmp.append(".");
					tmp.append(format.toLowerCase());
				}

				response.setHeader("Content-disposition", tmp.toString());
			}

			Exporter exporter;
			if (format.equalsIgnoreCase(FORMAT_PDF)) {
				response.setContentType("application/pdf");
				exporter = new JRPdfExporter();
				exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(response.getOutputStream()));
			} else if (format.equalsIgnoreCase(FORMAT_XLS)) {
				response.setContentType("application/vnd.ms-excel");
				exporter = new JRXlsExporter();
				exporter.setExporterOutput(new SimpleOutputStreamExporterOutput(response.getOutputStream()));
			} else if (format.equalsIgnoreCase(FORMAT_HTML)) {
				response.setContentType("text/html");
				exporter = new HtmlExporter();
				exporter.setExporterOutput(new SimpleHtmlExporterOutput(response.getWriter()));
			} else if (format.equalsIgnoreCase(FORMAT_CSV)) {
				response.setContentType("text/csv");
				exporter = new JRCsvExporter();
				exporter.setExporterOutput(new SimpleWriterExporterOutput(response.getWriter()));
			} else if (format.equalsIgnoreCase(FORMAT_XML)) {
				response.setContentType("text/xml");
				exporter = new JRXmlExporter();
				exporter.setExporterOutput(new SimpleWriterExporterOutput(response.getWriter()));
			} else if (format.equalsIgnoreCase(FORMAT_RTF)) {
				response.setContentType("application/rtf");
				exporter = new JRRtfExporter();
				exporter.setExporterOutput(new SimpleWriterExporterOutput(response.getWriter()));
			} else {
				throw new ServletException("Unknown report format: " + format);
			}

			Map<String, Object> exportParams = (Map) stack.findValue(exportParameters);
			if (exportParams != null) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Found export parameters; adding to exporter parameters...", new Object[0]);
				}
				for (Map.Entry<String, Object> entry : exportParams.entrySet())
					exporter.getReportContext().setParameterValue(entry.getKey(), entry.getValue());
			}
			exporter.setExporterInput(new SimpleExporterInput(jasperPrint));
			exporter.exportReport();
		} catch (JRException e) {
			String message = "Error producing " + format + " report for uri " + systemId;
			LOG.error(message, e);
			throw new ServletException(e.getMessage(), e);
		}

	}

	/**
	 * Sets up result properties, parsing etc.
	 *
	 * @param invocation
	 *            Current invocation.
	 * @throws Exception
	 *             on initialization error.
	 */
	private void initializeProperties(ActionInvocation invocation) throws Exception {
		if (dataSource == null && connection == null) {
			String message = "No dataSource specified...";
			LOG.error(message);
			throw new RuntimeException(message);
		}
		if (dataSource != null)
			dataSource = conditionalParse(dataSource, invocation);

		format = conditionalParse(format, invocation);
		if (StringUtils.isEmpty(format)) {
			format = FORMAT_PDF;
		}

		if (contentDisposition != null) {
			contentDisposition = conditionalParse(contentDisposition, invocation);
		}

		if (documentName != null) {
			documentName = conditionalParse(documentName, invocation);
		}

		reportParameters = conditionalParse(reportParameters, invocation);
		exportParameters = conditionalParse(exportParameters, invocation);
	}

}