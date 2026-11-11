package io.github.artemdbondarenko.crawdad.service.tls;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import io.github.artemdbondarenko.crawdad.service.ServiceConfig.TlsFiles;
import io.github.artemdbondarenko.crawdad.service.log.LogText;
import io.github.artemdbondarenko.crawdad.service.tls.TlsMaterial.UnusableFileException;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.eclipse.jetty.util.ssl.SslContextFactory;

/**
 * Keeps the network address on the current certificate files. A change in the directory of the certificate
 * or of the key starts a {@link #PAUSE}, and each further change starts it again; then both files are read
 * as at start and swapped into the TLS context for new connections. A pair that cannot serve, or that names
 * another address, leaves the previous one in place with a WARNING. At start and every day a WARNING says
 * when the served certificate has less than {@link #EXPIRY_NOTICE} left.
 */
public final class CertificateWatcher implements AutoCloseable {

    /** The quiet time after the last change before the files are read: a renewal writes them one by one. */
    public static final Duration PAUSE = Duration.ofSeconds(1);

    /** A served certificate with less time than this left is warned about. */
    public static final Duration EXPIRY_NOTICE = Duration.ofDays(14);

    static final Duration EXPIRY_CHECK_INTERVAL = Duration.ofDays(1);

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);
    private static final Logger LOG = System.getLogger(CertificateWatcher.class.getName());

    private final TlsFiles files;
    private final SslContextFactory.Server tls;
    private final String addressName;
    private final Clock clock;
    private final Duration pause;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            task -> Thread.ofPlatform().name("crawdad-certificate-reload").daemon().unstarted(task));
    /** Null when the directories could not be watched. */
    private final WatchService watchService;
    /** The names of the watched files in the directory of each key. */
    private final Map<WatchKey, Set<Path>> watched;
    private final Thread watchThread;
    private volatile Instant notAfter;
    /** Guarded by {@code this}. */
    private ScheduledFuture<?> pendingReload;

    private CertificateWatcher(TlsFiles files, SslContextFactory.Server tls, String addressName, Instant notAfter,
            Clock clock, Duration pause) {
        this.files = files;
        this.tls = tls;
        this.addressName = addressName;
        this.notAfter = notAfter;
        this.clock = clock;
        this.pause = pause;
        Map<WatchKey, Set<Path>> keys = new HashMap<>();
        this.watchService = watch(files, keys);
        this.watched = Map.copyOf(keys);
        this.watchThread = Thread.ofPlatform().name("crawdad-certificate-watcher").daemon().unstarted(this::run);
    }

    /**
     * Starts watching {@code files}, served by {@code tls} under {@code addressName} until {@code notAfter}.
     * Never fails: unwatchable directories leave only the expiry check, with a WARNING.
     */
    public static CertificateWatcher start(TlsFiles files, SslContextFactory.Server tls, String addressName,
            Instant notAfter) {
        return start(files, tls, addressName, notAfter, Clock.systemUTC(), PAUSE, EXPIRY_CHECK_INTERVAL);
    }

    /** As {@link #start(TlsFiles, SslContextFactory.Server, String, Instant)}, with the clock and periods given. */
    static CertificateWatcher start(TlsFiles files, SslContextFactory.Server tls, String addressName,
            Instant notAfter, Clock clock, Duration pause, Duration checkInterval) {
        CertificateWatcher watcher = new CertificateWatcher(files, tls, addressName, notAfter, clock, pause);
        watcher.checkExpiry();
        watcher.scheduler.scheduleAtFixedRate(watcher::checkExpiry, checkInterval.toMillis(),
                checkInterval.toMillis(), TimeUnit.MILLISECONDS);
        if (watcher.watchService != null) {
            watcher.watchThread.start();
        }
        return watcher;
    }

    /** Stops watching and waits for its threads to end. Safe to call more than once. */
    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (watchService != null) {
                watchService.close();
                watchThread.join(STOP_TIMEOUT);
            }
            scheduler.awaitTermination(STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Closing the certificate watch failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Registers the directory of each file once; on failure closes what was opened and returns null. */
    private static WatchService watch(TlsFiles files, Map<WatchKey, Set<Path>> keys) {
        Map<Path, Set<Path>> directories = new LinkedHashMap<>();
        for (Path file : new Path[] {files.certificate(), files.privateKey()}) {
            Path absolute = file.toAbsolutePath();
            directories.computeIfAbsent(absolute.getParent(), directory -> new HashSet<>())
                    .add(absolute.getFileName());
        }
        WatchService service = null;
        try {
            service = files.certificate().getFileSystem().newWatchService();
            for (Map.Entry<Path, Set<Path>> directory : directories.entrySet()) {
                keys.put(directory.getKey().register(service, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE),
                        directory.getValue());
            }
            return service;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Certificate files are not watched, a renewed certificate needs a restart"
                    + " of the service: {0}", LogText.cause(e));
            closeQuietly(service);
            return null;
        }
    }

    private static void closeQuietly(WatchService service) {
        if (service == null) {
            return;
        }
        try {
            service.close();
        } catch (IOException e) {
            LOG.log(Level.DEBUG, "Closing the certificate watch failed", e);
        }
    }

    /** The watch thread: every change of a watched file, or lost events, (re)starts the pause. */
    private void run() {
        try {
            while (true) {
                WatchKey key = watchService.take();
                Set<Path> names = watched.get(key);
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    relevant |= event.kind() == OVERFLOW || names.contains(event.context());
                }
                if (relevant) {
                    scheduleReload();
                }
                key.reset();
            }
        } catch (ClosedWatchServiceException e) {
            // Closed by close(): the watch is over.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private synchronized void scheduleReload() {
        if (pendingReload != null) {
            pendingReload.cancel(false);
        }
        try {
            pendingReload = scheduler.schedule(this::reload, pause.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            // Closing: nothing to reload any more.
        }
    }

    /** Runs on the scheduler, so never at the same time as the expiry check. */
    private void reload() {
        TlsMaterial material;
        try {
            material = TlsMaterial.read(files, clock);
        } catch (UnusableFileException e) {
            // The message names the file with the problem, which may be the key.
            notReloaded(LogText.safe(e.getMessage()));
            return;
        }
        if (!material.addressName().equals(addressName)) {
            LOG.log(Level.WARNING, "Certificate {0} not reloaded: its name changed from {1} to {2};"
                    + " restart the service to serve it. Still serving the previous certificate.",
                    LogText.safe(files.certificate().toString()), LogText.safe(addressName),
                    LogText.safe(material.addressName()));
            return;
        }
        try {
            // The key store lives only in memory; the password just satisfies the API.
            String password = UUID.randomUUID().toString();
            KeyStore store = material.keyStore(password.toCharArray());
            tls.reload(factory -> {
                factory.setKeyStore(store);
                factory.setKeyStorePassword(password);
            });
        } catch (Exception e) {
            notReloaded(LogText.safe(files.certificate().toString()) + ": " + LogText.cause(e));
            return;
        }
        notAfter = material.notAfter();
        LOG.log(Level.INFO, "Certificate reloaded from {0}, valid until {1}",
                LogText.safe(files.certificate().toString()), notAfter.toString());
    }

    /** {@code reason}, already safe, starts with the file it is about. */
    private static void notReloaded(String reason) {
        LOG.log(Level.WARNING, "Certificate not reloaded, still serving the previous one: {0}", reason);
    }

    private void checkExpiry() {
        Instant end = notAfter;
        if (clock.instant().plus(EXPIRY_NOTICE).isAfter(end)) {
            LOG.log(Level.WARNING, "Certificate {0} expires on {1}, in less than {2} days;"
                    + " renew it, the service picks up the renewed files by itself",
                    LogText.safe(files.certificate().toString()), end.toString(),
                    String.valueOf(EXPIRY_NOTICE.toDays()));
        }
    }
}
