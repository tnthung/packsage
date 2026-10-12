package dev.packsage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PackSageLog {
    public static final Logger LOGGER = LoggerFactory.getLogger("PackSage");

    private PackSageLog() {
    }

    public static String describe(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) cause = cause.getCause();
        String message = cause.getMessage();
        String text = cause.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }
}
