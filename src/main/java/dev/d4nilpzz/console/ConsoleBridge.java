package dev.d4nilpzz.console;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one console command and returns what it reported, for the dashboard's terminal tab.
 * <p>
 * Output is collected from the logger rather than from stdout. {@link CommandConsole} reports
 * through SLF4J, so capturing {@code System.out} returned an empty string for every command
 * except {@code performance}. Reading the logger also removes the process-wide
 * {@code System.setOut} swap this used to rely on, which had no {@code finally}: a command
 * failing with an {@link Error} left the real stdout redirected into a dead buffer for the
 * life of the process, and two commands running at once stole each other's output.
 */
public class ConsoleBridge {

    private final CommandConsole console;

    public ConsoleBridge(CommandConsole console) {
        this.console = console;
    }

    public String execute(String command) {
        Logger logger = logbackLogger();
        if (logger == null) {
            return executeWithoutCapture(command);
        }

        Capture capture = new Capture(Thread.currentThread().getName());
        capture.setContext(logger.getLoggerContext());
        capture.start();
        logger.addAppender(capture);

        try {
            console.handleCommand(command);
        } catch (Exception e) {
            capture.add(failureLine(e));
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }

        return capture.text();
    }

    private String executeWithoutCapture(String command) {
        try {
            console.handleCommand(command);
            return "";
        } catch (Exception e) {
            return failureLine(e);
        }
    }

    private static String failureLine(Exception e) {
        String message = e.getMessage();
        return "Command failed: " + (message == null ? e.getClass().getSimpleName() : message);
    }

    /** Null when SLF4J is not bound to Logback, in which case there is nothing to attach to. */
    private static Logger logbackLogger() {
        org.slf4j.Logger logger = LoggerFactory.getLogger(CommandConsole.class);
        return logger instanceof Logger logback ? logback : null;
    }

    /**
     * Keeps only the events logged by the thread that started the command, so two commands
     * running at once do not collect each other's lines.
     */
    private static final class Capture extends AppenderBase<ILoggingEvent> {

        private final String threadName;
        private final List<String> lines = new ArrayList<>();

        private Capture(String threadName) {
            this.threadName = threadName;
            setName("console-capture-" + threadName);
        }

        @Override
        protected void append(ILoggingEvent event) {
            if (threadName.equals(event.getThreadName())) {
                add(event.getFormattedMessage());
            }
        }

        private synchronized void add(String line) {
            lines.add(line);
        }

        private synchronized String text() {
            return String.join(System.lineSeparator(), lines);
        }
    }
}
