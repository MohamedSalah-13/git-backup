package com.gitbackup.store;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Keeps one window per user. The application stays in the tray after its window is closed,
 * so starting it again must bring that window back instead of opening a second copy.
 *
 * <p>The first instance holds a file lock and listens on a loopback port; a later one
 * connects to that port, which asks the first to show itself, and then exits.
 */
public final class SingleInstance implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;
    private final ServerSocket server;

    private SingleInstance(FileChannel channel, FileLock lock, ServerSocket server) {
        this.channel = channel;
        this.lock = lock;
        this.server = server;
    }

    /**
     * Returns the handle for this (first) instance, or null when another instance is
     * already running; in that case it has been asked to show its window.
     */
    public static SingleInstance acquire(AppPaths paths, Runnable onActivate) throws IOException {
        Files.createDirectories(paths.home());
        Path lockFile = paths.home().resolve("instance.lock");
        Path portFile = paths.home().resolve("instance.port");
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            channel.close();
            signal(portFile);
            return null;
        }
        ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        Files.writeString(portFile, Integer.toString(server.getLocalPort()), StandardCharsets.US_ASCII);
        Thread listener = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket ignored = server.accept()) {
                    onActivate.run();
                } catch (IOException e) {
                    // Closed on exit, or a failed connection: nothing to do.
                }
            }
        }, "single-instance");
        listener.setDaemon(true);
        listener.start();
        return new SingleInstance(channel, lock, server);
    }

    private static void signal(Path portFile) {
        try {
            int port = Integer.parseInt(Files.readString(portFile, StandardCharsets.US_ASCII).trim());
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2000);
            }
        } catch (IOException | RuntimeException e) {
            // The other instance is starting or stopping; this one still must not open.
        }
    }

    @Override
    public void close() {
        try {
            server.close();
            lock.release();
            channel.close();
        } catch (IOException ignored) {
            // The process is exiting.
        }
    }
}
