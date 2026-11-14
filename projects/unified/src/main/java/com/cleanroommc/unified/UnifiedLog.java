package com.cleanroommc.unified;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Logging goes through log4j, which both Forge and Cleanroom provide.
 */
final class UnifiedLog {

    private static final Logger LOGGER = LogManager.getLogger("CleanroomUnified");

    private UnifiedLog() {
    }

    static void info(String message, Object... params) {
        LOGGER.info(message, params);
    }

    static void warn(String message, Object... params) {
        LOGGER.warn(message, params);
    }

    static void error(String message, Throwable t) {
        LOGGER.error(message, t);
    }
}
