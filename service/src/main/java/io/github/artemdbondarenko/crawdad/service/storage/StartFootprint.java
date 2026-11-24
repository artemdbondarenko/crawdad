package io.github.artemdbondarenko.crawdad.service.storage;

import io.github.artemdbondarenko.crawdad.service.log.LogText;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * What was on disk before a start, so that a refused start leaves the disk as it found it ({@code START-2}): every
 * file and directory of the service and the parents it creates for them. Whatever a refused start added is deleted
 * again, whatever its name; what was there before is left alone.
 */
public final class StartFootprint {

    private static final Logger LOG = System.getLogger(StartFootprint.class.getName());

    private final List<Path> roots;
    private final Set<Path> before;
    private final List<Path> missingParents;
    private List<Path> added;

    private StartFootprint(List<Path> roots, Set<Path> before, List<Path> missingParents) {
        this.roots = roots;
        this.before = before;
        this.missingParents = missingParents;
    }

    /**
     * Notes what is in the data and runtime directories of {@code data} and which of their parents are missing.
     * Empty when the directories cannot be listed: then nothing could be told apart from the owner's files, and a
     * refused start deletes nothing.
     */
    public static Optional<StartFootprint> of(DataDirectory data) {
        List<Path> roots = List.copyOf(new LinkedHashSet<>(List.of(data.data(), data.runtime())));
        Set<Path> before = new HashSet<>();
        List<Path> missingParents = new ArrayList<>();
        try {
            for (Path root : roots) {
                for (Path missing = root; missing != null && !exists(missing); missing = missing.getParent()) {
                    if (!missingParents.contains(missing)) {
                        missingParents.add(missing);
                    }
                }
                before.addAll(walk(root));
            }
        } catch (IOException | UncheckedIOException e) {
            LOG.log(Level.DEBUG, "Cannot list the files of the service; a refused start leaves what it created", e);
            return Optional.empty();
        }
        return Optional.of(new StartFootprint(roots, before, missingParents));
    }

    /**
     * Deletes the files the start added, the control socket and the instance lock among them, keeping the
     * directories. Called while the instance lock is still held, so that no other start adds files meanwhile.
     */
    public void deleteAddedFiles() {
        for (Path path : added()) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                delete(path);
            }
        }
    }

    /**
     * Deletes the directories the start added, deepest first, and the parents it created for them. A directory
     * that is not empty holds what someone else put there since, and stays.
     */
    public void deleteAddedDirectories() {
        Set<Path> directories = new LinkedHashSet<>(added());
        directories.addAll(missingParents);
        List<Path> deepestFirst = new ArrayList<>(directories);
        deepestFirst.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        for (Path directory : deepestFirst) {
            if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                delete(directory);
            }
        }
    }

    /**
     * What is in the directories now and was not before, deepest first. Listed once: deleting the files does not
     * add a directory, so the directories found with them are the ones to delete after.
     */
    private List<Path> added() {
        if (added != null) {
            return added;
        }
        Set<Path> found = new LinkedHashSet<>();
        for (Path root : roots) {
            try {
                for (Path path : walk(root)) {
                    if (!before.contains(path)) {
                        found.add(path);
                    }
                }
            } catch (IOException | UncheckedIOException e) {
                LOG.log(Level.WARNING, "Cannot list what the refused start left in "
                        + LogText.safe(root.toString()), e);
            }
        }
        List<Path> deepestFirst = new ArrayList<>(found);
        deepestFirst.sort(Comparator.comparingInt(Path::getNameCount).reversed());
        added = deepestFirst;
        return added;
    }

    /**
     * Everything in {@code root}, without following links inside it; {@code root} itself is followed when it is a
     * link, as the service follows it when it writes there.
     */
    private static List<Path> walk(Path root) throws IOException {
        if (!exists(root)) {
            return List.of();
        }
        Path start = Files.isSymbolicLink(root) ? root.toRealPath() : root;
        try (Stream<Path> paths = Files.walk(start)) {
            return paths.toList();
        }
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static void delete(Path path) {
        try {
            Files.delete(path);
        } catch (NoSuchFileException | DirectoryNotEmptyException e) {
            // Gone already, or holds what someone else put there since.
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot delete " + LogText.safe(path.toString()) + ", left by the refused start", e);
        }
    }
}
