package org.watermedia.api.player;

import com.sun.jna.Platform;
import org.watermedia.videolan4j.discovery.providers.IProvider;

import java.io.File;
import java.util.regex.Pattern;

/**
 * Discovers the VLC libraries supplied by the Eufonia Client Flatpak extension.
 *
 * <p>The extension's executable is merged into {@code /app/extensions/bin}, but
 * its libraries remain under {@code /app/extensions/VLC/lib}. The standard
 * Linux provider does not walk from the merged executable to that directory.</p>
 */
public final class EufoniaProvider implements IProvider {
    static final String VLC_LIB_DIRECTORY = "/app/extensions/VLC/lib";

    private static final Pattern LIBVLC = Pattern.compile("libvlc\\.so(?:\\.\\d+)*");
    private static final Pattern LIBVLCCORE = Pattern.compile("libvlccore\\.so(?:\\.\\d+)*");

    private final File directory;

    public EufoniaProvider() {
        this(new File(VLC_LIB_DIRECTORY));
    }

    EufoniaProvider(File directory) {
        this.directory = directory;
    }

    @Override
    public String name() {
        return "Eufonia Client VLC Provider";
    }

    @Override
    public Priority priority() {
        return Priority.HIGHEST;
    }

    @Override
    public boolean supported() {
        return Platform.isLinux() && isVlcDirectory(directory);
    }

    @Override
    public String[] directories() {
        return new String[] {directory.getAbsolutePath()};
    }

    private static boolean isVlcDirectory(File directory) {
        if (directory == null || !directory.isDirectory() || !directory.canRead() || !directory.canExecute()) {
            return false;
        }

        final File plugins = new File(directory, "vlc/plugins");
        if (!plugins.isDirectory() || !plugins.canRead() || !plugins.canExecute()) {
            return false;
        }

        return containsLibrary(directory, LIBVLC) && containsLibrary(directory, LIBVLCCORE);
    }

    private static boolean containsLibrary(File directory, Pattern pattern) {
        final File[] files = directory.listFiles();
        if (files == null) {
            return false;
        }

        for (final File file : files) {
            if (file.isFile() && pattern.matcher(file.getName()).matches()) {
                return true;
            }
        }
        return false;
    }
}
