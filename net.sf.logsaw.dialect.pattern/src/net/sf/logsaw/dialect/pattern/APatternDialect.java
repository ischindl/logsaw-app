/*******************************************************************************
 * Copyright (c) 2010, 2011 LogSaw project and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    LogSaw project committers - initial API and implementation
 *******************************************************************************/
package net.sf.logsaw.dialect.pattern;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.sf.logsaw.core.config.IConfigOption;
import net.sf.logsaw.core.config.IConfigOptionVisitor;
import net.sf.logsaw.core.config.model.StringConfigOption;
import net.sf.logsaw.core.dialect.ILogEntryCollector;
import net.sf.logsaw.core.dialect.ILogFieldProvider;
import net.sf.logsaw.core.dialect.support.ALogDialect;
import net.sf.logsaw.core.dialect.support.FilteringFieldProvider;
import net.sf.logsaw.core.field.ALogEntryField;
import net.sf.logsaw.core.field.LogEntry;
import net.sf.logsaw.core.logresource.IHasEncoding;
import net.sf.logsaw.core.logresource.IHasLocale;
import net.sf.logsaw.core.logresource.IHasTimeZone;
import net.sf.logsaw.core.logresource.IHasTimestampPattern;
import net.sf.logsaw.core.logresource.ILogResource;
import net.sf.logsaw.dialect.pattern.internal.Messages;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.LineIterator;
import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.MultiStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.osgi.util.NLS;

/**
 * @author Philipp Nanz
 */
public abstract class APatternDialect extends ALogDialect implements IHasTimestampPattern {

	public static final StringConfigOption OPTION_PATTERN = 
		new StringConfigOption("pattern", "Conversion Pattern"); //$NON-NLS-1$ //$NON-NLS-2$

	private IConversionPatternTranslator patternTranslator;
	private ILogFieldProvider fieldProvider;
	private List<ConversionRule> rules;
	private Pattern internalPatternFirstLine;
	private Pattern internalPatternFull;
	private String timestampPattern;

	/**
	 * @return the patternTranslator
	 */
	public IConversionPatternTranslator getPatternTranslator() {
		return patternTranslator;
	}

	/**
	 * @param patternTranslator the patternTranslator to set
	 */
	public void setPatternTranslator(
			IConversionPatternTranslator patternTranslator) {
		this.patternTranslator = patternTranslator;
	}

	/**
	 * @return the rules
	 */
	public List<ConversionRule> getRules() {
		return rules;
	}

	/**
	 * @param rules the rules to set
	 */
	public void setRules(List<ConversionRule> rules) {
		this.rules = rules;
	}

	/**
	 * @return the internalPatternFirstLine
	 */
	public Pattern getInternalPatternFirstLine() {
		return internalPatternFirstLine;
	}

	/**
	 * @param internalPatternFirstLine the internalPatternFirstLine to set
	 */
	public void setInternalPatternFirstLine(Pattern internalPatternFirstLine) {
		this.internalPatternFirstLine = internalPatternFirstLine;
	}

	/**
	 * @return the internalPatternFull
	 */
	public Pattern getInternalPatternFull() {
		return internalPatternFull;
	}

	/**
	 * @param internalPatternFull the internalPatternFull to set
	 */
	public void setInternalPatternFull(Pattern internalPatternFull) {
		this.internalPatternFull = internalPatternFull;
	}

	/* (non-Javadoc)
	 * @see net.sf.logsaw.core.framework.ILogDialect#getFieldProvider()
	 */
	@Override
	public final ILogFieldProvider getFieldProvider() {
		return fieldProvider;
	}

	/**
	 * @param fieldProvider the fieldProvider to set
	 */
	public void setFieldProvider(ILogFieldProvider fieldProvider) {
		this.fieldProvider = fieldProvider;
	}

	/* (non-Javadoc)
	 * @see net.sf.logsaw.core.logresource.IHasTimestampPattern#getTimestampPattern()
	 */
	@Override
	public String getTimestampPattern() {
		return timestampPattern;
	}

	/**
	 * Creates the pattern translator.
	 * @return the pattern translator
	 */
	protected abstract IConversionPatternTranslator doCreatePatternTranslator();

	/**
	 * Creates the base field provider.
	 * @return the base field provider
	 */
	protected abstract ILogFieldProvider doCreateFieldProvider();

	/**
	 * Returns the default conversion pattern or <code>null</code>
	 * @return the default conversion pattern
	 */
	protected String doGetDefaultConversionPattern() {
		return null;
	}

	/**
	 * Calculates the <code>followedByQuotedString</code> for the specified conversion rules.
	 * @param externalPattern the external pattern
	 * @param rules the conversion rules
	 */
	protected void fillFollowedByQuotedString(String externalPattern, List<ConversionRule> rules) {
		// Determine whether rules are followed by quoted string, allowing use of special Regex lazy modifiers
		int idx = 0;
		ConversionRule prevRule = null;
		for (ConversionRule rule : rules) {
			if ((rule.getBeginIndex() > idx) && (prevRule != null)) {
				// Previous rule is followed by a quoted string, allowing special regex flags
				prevRule.setFollowedByQuotedString(true);
			}
			idx = rule.getBeginIndex();
			idx += rule.getLength();
			prevRule = rule;
		}
		if ((externalPattern.length() > idx) && (prevRule != null)) {
			// Previous rule is followed by a quoted string, allowing special regex flags
			prevRule.setFollowedByQuotedString(true);
		}
	}

	/**
	 * Converts the given external pattern to the internal Regex pattern using the specified conversion rules.
	 * @param externalPattern the external pattern
	 * @param patternTranslator the conversion pattern translator
	 * @param rules the conversion rules
	 * @return the internal pattern
	 * @throws CoreException if an error occurred
	 */
	protected String toRegexPattern(String externalPattern, IConversionPatternTranslator patternTranslator, 
			List<ConversionRule> rules, boolean firstLineOnly) throws CoreException {
		// Build the internal Regex pattern
		StringBuilder sb = new StringBuilder();
		int idx = 0;
		for (ConversionRule rule : rules) {
			if (firstLineOnly && rule.isLineBreak()) {
				// That's it
				return sb.toString();
			}
			if (rule.getBeginIndex() > idx) {
				// Escape chars with special meaning
				sb.append(Pattern.quote(externalPattern.substring(idx, rule.getBeginIndex())));
			}
			idx = rule.getBeginIndex();
			String regex = patternTranslator.getRegexPatternForRule(rule);
			Assert.isNotNull(regex, "regex"); //$NON-NLS-1$
			sb.append(regex);
			idx += rule.getLength();
		}
		if (externalPattern.length() > idx) {
			// Append suffix
			sb.append(Pattern.quote(externalPattern.substring(idx)));
		}
		return sb.toString();
	}

	/**
	 * Extracts the available fields from the given rules.
	 * @param rules the conversion rules
	 * @return the list of fields
	 * @throws CoreException if an error occurred
	 */
	protected List<ALogEntryField<?, ?>> extractFields(List<ConversionRule> rules) throws CoreException {
		List<ALogEntryField<?, ?>> ret = new ArrayList<ALogEntryField<?,?>>();
		for (ConversionRule rule : rules) {
			ALogEntryField<?, ?> fld = getPatternTranslator().getFieldForRule(rule);
			if (fld != null) {
				ret.add(fld);
			}
		}
		return ret;
	}

	/* (non-Javadoc)
	 * @see net.sf.logsaw.core.framework.support.AConfigurableLogDialect#configureDefaults()
	 */
	@Override
	public void configureDefaults() throws CoreException {
		String externalPattern = doGetDefaultConversionPattern();
		if (externalPattern != null) {
			// Configure default pattern (if any)
			configure(OPTION_PATTERN, externalPattern);
		}
		super.configureDefaults();
	}

	/* (non-Javadoc)
	 * @see net.sf.logsaw.core.framework.ALogDialect#configure(net.sf.logsaw.core.config.IConfigOption, java.lang.Object)
	 */
	@Override
	public <T> void configure(IConfigOption<T> option, T value)
			throws CoreException {
		option.visit(new IConfigOptionVisitor() {
			/* (non-Javadoc)
			 * @see net.sf.logsaw.core.config.IConfigOptionVisitor#visit(net.sf.logsaw.core.config.StringConfigOption, java.lang.String)
			 */
			@Override
			public void visit(StringConfigOption opt, String value) throws CoreException {
				if (OPTION_TIMESTAMP_PATTERN.equals(opt)) {
					Assert.isNotNull(value, "timestampPattern"); //$NON-NLS-1$
					timestampPattern = value;
				} else if (OPTION_PATTERN.equals(opt)) {
					Assert.isNotNull(value, "externalPattern"); //$NON-NLS-1$
					getLogger().info("Configuring conversion pattern " + value); //$NON-NLS-1$
					
					// Create pattern translator
					IConversionPatternTranslator translator = doCreatePatternTranslator();
					Assert.isNotNull(translator, "patternTranslator"); //$NON-NLS-1$
					setPatternTranslator(translator);
					
					// Extract rules from external pattern
					value = translator.prepare(value);
					Assert.isNotNull(value, "externalPattern"); //$NON-NLS-1$
					List<ConversionRule> rules = translator.extractRules(value);
					Assert.isNotNull(rules, "rules"); //$NON-NLS-1$
					if (rules.isEmpty()) {
						throw new CoreException(new Status(IStatus.ERROR, PatternDialectPlugin.PLUGIN_ID, 
								Messages.APatternDialect_error_invalidPattern));
					}
					for (ConversionRule rule : rules) {
						// Apply default modifiers
						translator.applyDefaults(rule, APatternDialect.this);
						// Rewrite rules
						translator.rewrite(rule, APatternDialect.this);
					}
					fillFollowedByQuotedString(value, rules);
					setRules(rules);
					
					// Convert rules to Regex (internal) pattern
					try {
						Pattern internalPatternFirstLine = Pattern.compile(toRegexPattern(value, translator, rules, true));
						getLogger().debug("Internal Pattern (first line): " + internalPatternFirstLine.pattern()); //$NON-NLS-1$
						setInternalPatternFirstLine(internalPatternFirstLine);
						Pattern internalPatternFull = Pattern.compile(toRegexPattern(value, translator, rules, false));
						getLogger().debug("Internal Pattern (full): " + internalPatternFull.pattern()); //$NON-NLS-1$
						setInternalPatternFull(internalPatternFull);
					} catch (PatternSyntaxException e) {
						throw new CoreException(new Status(IStatus.ERROR, PatternDialectPlugin.PLUGIN_ID, 
								NLS.bind(Messages.APatternDialect_error_failedToTranslateToRegex, value)));
					}
					
					// Setup field provider
					List<ALogEntryField<?, ?>> fields = extractFields(rules);
					ILogFieldProvider innerProvider = doCreateFieldProvider();
					Assert.isNotNull(innerProvider, "fieldProvider"); //$NON-NLS-1$
					setFieldProvider(new FilteringFieldProvider(innerProvider, fields));
				}
			}
		}, value);
		super.configure(option, value);
	}

	/* (non-Javadoc)
	 * @see net.sf.logsaw.core.config.IConfigurableObject#validate(net.sf.logsaw.core.config.IConfigOption, java.lang.Object)
	 */
	@Override
	public <T> void validate(IConfigOption<T> option, T value)
			throws CoreException {
		option.visit(new IConfigOptionVisitor() {
			/* (non-Javadoc)
			 * @see net.sf.logsaw.core.config.IConfigOptionVisitor#visit(net.sf.logsaw.core.config.StringConfigOption, java.lang.String)
			 */
			@Override
			public void visit(StringConfigOption opt, String value) throws CoreException {
				if (OPTION_PATTERN.equals(opt)) {
					Assert.isNotNull(value, "externalPattern"); //$NON-NLS-1$
					getLogger().info("Validating conversion pattern " + value); //$NON-NLS-1$
					
					// Create pattern translator
					IConversionPatternTranslator translator = doCreatePatternTranslator();
					Assert.isNotNull(translator, "patternTranslator"); //$NON-NLS-1$
					
					// Extract rules from external pattern
					value = translator.prepare(value);
					Assert.isNotNull(value, "externalPattern"); //$NON-NLS-1$
					List<ConversionRule> rules = translator.extractRules(value);
					Assert.isNotNull(rules, "rules"); //$NON-NLS-1$
					if (rules.isEmpty()) {
						throw new CoreException(new Status(IStatus.ERROR, PatternDialectPlugin.PLUGIN_ID, 
								Messages.APatternDialect_error_invalidPattern));
					}
					for (ConversionRule rule : rules) {
						// Apply default modifiers
						translator.applyDefaults(rule, APatternDialect.this);
						// Rewrite rules
						translator.rewrite(rule, APatternDialect.this);
					}
					fillFollowedByQuotedString(value, rules);
					
					// Convert rules to Regex (internal) pattern
					try {
						Pattern internalPatternFirstLine = Pattern.compile(toRegexPattern(value, translator, rules, true));
						getLogger().debug("Internal Pattern (first line): " + internalPatternFirstLine.pattern()); //$NON-NLS-1$
						Pattern internalPatternFull = Pattern.compile(toRegexPattern(value, translator, rules, false));
						getLogger().debug("Internal Pattern (full): " + internalPatternFull.pattern()); //$NON-NLS-1$
					} catch (PatternSyntaxException e) {
						throw new CoreException(new Status(IStatus.ERROR, PatternDialectPlugin.PLUGIN_ID, 
								NLS.bind(Messages.APatternDialect_error_failedToTranslateToRegex, value)));
					}
				}
			}
		}, value);
		super.validate(option, value);
	}

	/* (non-Javadoc)
	 * @see net.sf.logsaw.core.framework.AConfigurableLogDialect#getSupportedConfigOptions()
	 */
	@Override
	public List<IConfigOption<?>> getRequiredConfigOptions() {
		List<IConfigOption<?>> ret = new ArrayList<IConfigOption<?>>();
		ret.add(OPTION_PATTERN);
		return ret;
	}

	/* (non-Javadoc)
	 * @see net.sf.logsaw.core.framework.ILogDialect#parse(net.sf.logsaw.core.framework.ILogResource, java.io.InputStream, net.sf.logsaw.core.framework.ILogEntryCollector)
	 */
	@Override
	public void parse(ILogResource log, InputStream input,
			ILogEntryCollector collector) throws CoreException {
		Assert.isNotNull(log, "log"); //$NON-NLS-1$
		Assert.isNotNull(input, "input"); //$NON-NLS-1$
		Assert.isNotNull(collector, "collector"); //$NON-NLS-1$
		Assert.isTrue(isConfigured(), "Dialect should be configured by now"); //$NON-NLS-1$
		try {
			LogEntry currentEntry = null;
			IHasEncoding enc = (IHasEncoding) log.getAdapter(IHasEncoding.class);
			IHasLocale loc = (IHasLocale) log.getAdapter(IHasLocale.class);
			if (loc != null) {
				// Apply the locale
				getPatternTranslator().applyLocale(loc.getLocale(), rules);
			}
			IHasTimeZone tz = (IHasTimeZone) log.getAdapter(IHasTimeZone.class);
			if (tz != null) {
				// Apply the timezone
				getPatternTranslator().applyTimeZone(tz.getTimeZone(), rules);
			}
			LineIterator iter = IOUtils.lineIterator(input, enc.getEncoding());
			int minLinesPerEntry = getPatternTranslator().getMinLinesPerEntry();
			int lineNo = 0;
			int moreLinesToCome = 0;
			try {
				String line = null;
				while (iter.hasNext()) {
					lineNo++;
					
					if (minLinesPerEntry == 1) {
						// Simple case
						line = iter.nextLine();
					} else {
						String s = iter.nextLine();
						if (moreLinesToCome == 0) {
							Matcher m = getInternalPatternFirstLine().matcher(s);
							if (m.find()) {
								// First line
								line = s;
								moreLinesToCome = minLinesPerEntry - 1;
								continue;
							} else {
								// Some crazy stuff
								line = s;
							}
						} else if (iter.hasNext() && (moreLinesToCome > 1)) {
							// Some middle line
							line += IOUtils.LINE_SEPARATOR + s;
							moreLinesToCome--;
							continue;
						} else {
							// Last line
							line += IOUtils.LINE_SEPARATOR + s;
							if (!iter.hasNext()) {
								line += IOUtils.LINE_SEPARATOR;
							}
							moreLinesToCome = 0;
						}
					}
					
					// Error handling
					List<IStatus> statuses = null;
					boolean fatal = false; // determines whether to interrupt parsing
					
					Matcher m = getInternalPatternFull().matcher(line);
					if (m.find()) {
						// The next line matches, so flush the previous entry and continue
						if (currentEntry != null) {
							collector.collect(currentEntry);
							currentEntry = null;
						}
						currentEntry = new LogEntry();
						for (int i = 0; i < m.groupCount(); i++) {
							try {
								getPatternTranslator().extractField(currentEntry, getRules().get(i), 
										m.group(i + 1));
							} catch (CoreException e) {
								// Mark for interruption
								fatal = fatal || e.getStatus().matches(IStatus.ERROR);
								
								// Messages will be displayed later
								if (statuses == null) {
									statuses = new ArrayList<IStatus>();
								}
								if (e.getStatus().isMultiStatus()) {
									Collections.addAll(statuses, e.getStatus().getChildren());
								} else {
									statuses.add(e.getStatus());
								}
							}
						}
						
						// We encountered errors or warnings
						if (statuses != null && !statuses.isEmpty()) {
							currentEntry = null; // Stop propagation
							IStatus status = new MultiStatus(PatternDialectPlugin.PLUGIN_ID, 
									0, statuses.toArray(new IStatus[statuses.size()]), 
									NLS.bind(Messages.APatternDialect_error_failedToParseLine, lineNo), null);
							if (fatal) {
								// Interrupt parsing in case of error
								throw new CoreException(status);
							} else {
								collector.addMessage(status);
							}
						}
					} else if (currentEntry != null) {
						// Append to message
						String msg = currentEntry.get(getFieldProvider().getMessageField());
						currentEntry.put(getFieldProvider().getMessageField(), msg + IOUtils.LINE_SEPARATOR + line);
					}
					
					if (collector.isCanceled()) {
						// Cancel parsing
						break;
					}
				}
				
				if (currentEntry != null) {
					// Collect left over entry
					collector.collect(currentEntry);
				}
			} finally {
				LineIterator.closeQuietly(iter);
			}
		} catch (Exception e) {
			throw new CoreException(new Status(IStatus.ERROR, PatternDialectPlugin.PLUGIN_ID, 
					NLS.bind(Messages.APatternDialect_error_failedToParseFile, 
							new Object[] {log.getName(), e.getLocalizedMessage()}), e));
		}
	}
}
