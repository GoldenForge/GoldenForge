package gg.pufferfish.pufferfish.sentry;

import io.papermc.paper.configuration.GlobalConfiguration;
import io.sentry.Sentry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class SentryManager {
	
	private static final Logger logger = LogManager.getLogger(SentryManager.class);
	
	private SentryManager() {
	
	}
	
	private static boolean initialized = false;
	
	public static synchronized void init(GlobalConfiguration.PufferfishConfig pufferfishConfig) {
		if (initialized) {
			return;
		}
		try {
			initialized = true;
			
			Sentry.init(options -> {
				options.setDsn(pufferfishConfig.sentryDsn);
				options.setMaxBreadcrumbs(100);
			});
			
			PufferfishSentryAppender appender = new PufferfishSentryAppender();
			appender.start();
			((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).addAppender(appender);
			logger.info("Sentry logging started!");
		} catch (Exception e) {
			logger.warn("Failed to initialize sentry!", e);
			initialized = false;
		}
	}
	
}
