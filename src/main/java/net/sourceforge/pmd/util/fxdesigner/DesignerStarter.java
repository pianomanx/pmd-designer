/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.util.fxdesigner;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import javax.swing.JOptionPane;

import org.apache.commons.lang3.SystemUtils;

import net.sourceforge.pmd.annotation.InternalApi;

import javafx.application.Application;

/**
 * Main class of the app, checking for prerequisites to launching {@link Designer}.
 */
public final class DesignerStarter {

    private static final String MISSING_JAVAFX =
        "You seem to be missing the JavaFX runtime." + System.lineSeparator()
            + " Please install JavaFX on your system and try again." + System.lineSeparator()
            + " See https://gluonhq.com/products/javafx/";

    private static final String INCOMPATIBLE_JAVAFX =
        "You seem to be running an older version of JavaFX runtime." + System.lineSeparator()
            + " Please install the latest JavaFX on your system and try again." + System.lineSeparator()
            + " See https://gluonhq.com/products/javafx/";

    private static final int MIN_JAVAFX_VERSION_ON_MAC_OSX = 14;

    private DesignerStarter() {
    }

    private static boolean isJavaFxAvailable() {
        try {
            DesignerStarter.class.getClassLoader().loadClass("javafx.application.Application");
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    /**
     * Starting from PMD 7.0.0 this method usage will be limited for development.
     * CLI support will be provided by pmd-cli
     */
    @InternalApi
    public static void main(String[] args) {
        final ExitStatus ret = launchGui(args);
        System.exit(ret.getCode());
    }

    private static void setSystemProperties() {
        if (SystemUtils.IS_OS_LINUX) {
            // On Linux, JavaFX renders text poorly by default. These settings help to alleviate the problems.
            System.setProperty("prism.text", "t2k");
            System.setProperty("prism.lcdtext", "true");
        }
    }
    
    private static boolean isCompatibleJavaFxVersion() {
        if (SystemUtils.IS_OS_MAC_OSX) {
            final String javaFxVersion = getJavaFxVersion();
            if (javaFxVersion != null) {
                final int major = Integer.parseInt(javaFxVersion.split("\\.")[0]);
                if (major < MIN_JAVAFX_VERSION_ON_MAC_OSX) {
                    // Prior to JavaFx 14, text on Mac OSX was garbled and unreadable
                    return false;
                }
            }
        }

        return true;
    }

    private static String getJavaFxVersion() {
        try (InputStream is = DesignerStarter.class.getClassLoader().getResourceAsStream("javafx.properties")) {
            final Properties javaFxProperties = new Properties();
            javaFxProperties.load(is);
            return (String) javaFxProperties.get("javafx.version");
        } catch (IOException ignored) {
            // Can't determine the version
        }

        return null;
    }

    @SuppressWarnings("PMD.AvoidCatchingThrowable")
    public static ExitStatus launchGui(String[] args) {
        setSystemProperties();

        String message = null;
        if (!isJavaFxAvailable()) {
            message = MISSING_JAVAFX;
        } else if (!isCompatibleJavaFxVersion()) {
            message = INCOMPATIBLE_JAVAFX;
        }

        if (message != null) {
            System.err.println(message);
            JOptionPane.showMessageDialog(null, message);
            return ExitStatus.ERROR;
        }

        try {
            Application.launch(Designer.class, args);
        } catch (Throwable unrecoverable) {
            unrecoverable.printStackTrace();
            return ExitStatus.ERROR;
        }

        return ExitStatus.OK;
    }

    public enum ExitStatus {
        OK(0),
        ERROR(1);

        private final int code;

        ExitStatus(final int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }
    }
}
