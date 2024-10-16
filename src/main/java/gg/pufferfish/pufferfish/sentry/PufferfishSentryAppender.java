package gg.pufferfish.pufferfish.sentry;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import io.sentry.Breadcrumb;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import io.sentry.protocol.Message;
import io.sentry.protocol.User;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.filter.AbstractFilter;

import java.util.Map;

public class PufferfishSentryAppender extends AbstractAppender {
	
	private static final org.apache.logging.log4j.Logger logger = LogManager.getLogger(PufferfishSentryAppender.class);
	private static final Gson GSON = new Gson();
	
	public PufferfishSentryAppender() {
		super("PufferfishSentryAdapter", new SentryFilter(), null);
	}
	
	@Override
	public void append(LogEvent logEvent) {
		if (logEvent.getThrown() != null && logEvent.getLevel().isMoreSpecificThan(Level.WARN)) {
			try {
				logException(logEvent);
			} catch (Exception e) {
				logger.warn("Failed to log event with sentry", e);
			}
		} else {
			try {
				logBreadcrumb(logEvent);
			} catch (Exception e) {
				logger.warn("Failed to log event with sentry", e);
			}
		}
	}
	
	private void logException(LogEvent e) {
		SentryEvent event = new SentryEvent(e.getThrown());
		
		Message sentryMessage = new Message();
		sentryMessage.setMessage(e.getMessage().getFormattedMessage());
		
		event.setThrowable(e.getThrown());
		event.setLevel(getLevel(e.getLevel()));
		event.setLogger(e.getLoggerName());
		event.setTransaction(e.getLoggerName());
		event.setExtra("thread_name", e.getThreadName());
		
		boolean hasContext = e.getContextData() != null;
		
		if (hasContext && e.getContextData().containsKey("pufferfishsentry_playerid")) {
			User user = new User();
			user.setId(e.getContextData().getValue("pufferfishsentry_playerid"));
			user.setUsername(e.getContextData().getValue("pufferfishsentry_playername"));
			event.setUser(user);
		}
		
		if (hasContext && e.getContextData().containsKey("pufferfishsentry_pluginname")) {
			event.setExtra("plugin.name", e.getContextData().getValue("pufferfishsentry_pluginname"));
			event.setExtra("plugin.version", e.getContextData().getValue("pufferfishsentry_pluginversion"));
			event.setTransaction(e.getContextData().getValue("pufferfishsentry_pluginname"));
		}
		
		if (hasContext && e.getContextData().containsKey("pufferfishsentry_eventdata")) {
			Map<String, String> eventFields = GSON.fromJson((String) e.getContextData().getValue("pufferfishsentry_eventdata"), new TypeToken<Map<String, String>>() {}.getType());
			if (eventFields != null) {
				event.setExtra("event", eventFields);
			}
		}
		
		Sentry.captureEvent(event);
	}
	
	private void logBreadcrumb(LogEvent e) {
		Breadcrumb breadcrumb = new Breadcrumb();
		
		breadcrumb.setLevel(getLevel(e.getLevel()));
		breadcrumb.setCategory(e.getLoggerName());
		breadcrumb.setType(e.getLoggerName());
		breadcrumb.setMessage(e.getMessage().getFormattedMessage());
		
		Sentry.addBreadcrumb(breadcrumb);
	}
	
	private SentryLevel getLevel(Level level) {
		switch (level.getStandardLevel()) {
			case TRACE:
			case DEBUG:
				return SentryLevel.DEBUG;
			case WARN:
				return SentryLevel.WARNING;
			case ERROR:
				return SentryLevel.ERROR;
			case FATAL:
				return SentryLevel.FATAL;
			case INFO:
			default:
				return SentryLevel.INFO;
		}
	}
	
	private static class SentryFilter extends AbstractFilter {
		
		@Override
		public Result filter(Logger logger, Level level, Marker marker, String msg,
				Object... params) {
			return this.filter(logger.getName());
		}
		
		@Override
		public Result filter(Logger logger, Level level, Marker marker, Object msg, Throwable t) {
			return this.filter(logger.getName());
		}
		
		@Override
		public Result filter(LogEvent event) {
			return this.filter(event == null ? null : event.getLoggerName());
		}
		
		private Result filter(String loggerName) {
			return loggerName != null && loggerName.startsWith("gg.castaway.pufferfish.sentry") ? Result.DENY
					: Result.NEUTRAL;
		}
		
	}
}
