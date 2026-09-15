package io.github.amisonnet8.sandbox.internal.transport;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * A background daemon thread that reads raw bytes from a child process's
 * stdout, frames them into lines via {@link LineFramer}, and hands each
 * completed line to the caller through a 1-slot queue.
 *
 * <p>A background thread is the only way to put a deadline on reading
 * from an anonymous pipe: {@code Process.getInputStream()} has no
 * read-timeout mechanism. The socket transport does not use this -- see
 * {@code SocketTransport} for why (the TLS seam needs an inline framer
 * instead, and {@code java.net.Socket} has a real {@code setSoTimeout}
 * unlike a pipe).
 *
 * <p>The thread is a daemon so a stuck reader (see the class-level note on
 * {@link #join}) cannot keep the JVM alive, and {@code Thread.join(long)}
 * is a real bounded join in Java (unlike Rust's {@code JoinHandle}, which
 * has none), so unlike this driver's Rust counterpart, {@link #join} needs
 * no busy-wait/give-up dance.
 */
final class ReaderThread {

    private static final class Item {
        final byte[] line;
        final TransportException error;

        private Item(byte[] line, TransportException error) {
            this.line = line;
            this.error = error;
        }

        static Item line(byte[] data) {
            return new Item(data, null);
        }

        static Item terminal(TransportException e) {
            return new Item(null, e);
        }
    }

    private final ArrayBlockingQueue<Item> queue = new ArrayBlockingQueue<>(1);
    private final Thread thread;

    /**
     * The terminal error (EOF, line-cap exceeded, or I/O error), once
     * seen, is memoized and re-delivered on every subsequent call. Only
     * ever read and written from the single thread that calls {@link
     * #readLine} (Session serializes every call on this connection with a
     * lock), so this needs no {@code volatile}: the lock's
     * release/acquire pairs already establish the happens-before edges
     * between successive calls, even across different caller threads.
     */
    private TransportException terminal;

    ReaderThread(InputStream source) {
        thread = new Thread(() -> runLoop(source), "san-db-ox-reader");
        thread.setDaemon(true);
        thread.start();
    }

    private void runLoop(InputStream source) {
        LineFramer framer = new LineFramer();
        byte[] buf = new byte[64 * 1024];
        while (true) {
            int n;
            try {
                n = source.read(buf);
            } catch (IOException e) {
                putUninterruptibly(Item.terminal(new TransportException(TransportException.Kind.IO, e.getMessage(), e)));
                return;
            }
            if (n < 0) {
                putUninterruptibly(
                        Item.terminal(new TransportException(TransportException.Kind.CLOSED, "connection closed (EOF)")));
                return;
            }
            if (n == 0) {
                continue;
            }
            framer.push(buf, 0, n);
            while (true) {
                byte[] line;
                try {
                    line = framer.takeLine();
                } catch (TransportException e) {
                    putUninterruptibly(Item.terminal(e));
                    return;
                }
                if (line == null) {
                    break;
                }
                if (!putUninterruptibly(Item.line(line))) {
                    return; // interrupted; nothing left to do
                }
            }
        }
    }

    private boolean putUninterruptibly(Item item) {
        try {
            queue.put(item);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** {@code timeout == null} means wait forever. */
    byte[] readLine(Duration timeout) {
        if (terminal != null) {
            throw terminal;
        }
        Long deadlineNanos = (timeout == null) ? null : System.nanoTime() + timeout.toNanos();
        while (true) {
            Item item;
            try {
                if (deadlineNanos == null) {
                    item = queue.take();
                } else {
                    long remaining = deadlineNanos - System.nanoTime();
                    item = queue.poll(Math.max(remaining, 0), TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new TransportException(TransportException.Kind.IO, "interrupted while waiting for a response line", e);
            }
            if (item == null) {
                // poll() can return early/spuriously per its own contract, so this
                // re-checks the deadline rather than trusting a single timed call.
                if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                    throw new TransportException(TransportException.Kind.TIMEOUT, "timed out waiting for a response line");
                }
                continue;
            }
            if (item.error != null) {
                terminal = item.error;
                throw item.error;
            }
            return item.line;
        }
    }

    /**
     * Joins the background thread within {@code timeout}. If a request's
     * response was never drained via {@link #readLine} (see {@code
     * Session.close}'s doc comment for why that must never happen), the
     * thread could otherwise still be parked delivering it through the
     * 1-slot queue; a bounded join here, rather than an unconditional one,
     * ensures {@code close()} itself always returns.
     */
    void join(Duration timeout) {
        try {
            thread.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
