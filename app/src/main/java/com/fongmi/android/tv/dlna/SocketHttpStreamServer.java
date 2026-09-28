package com.fongmi.android.tv.dlna;

import org.jupnp.model.message.Connection;
import org.jupnp.model.message.StreamRequestMessage;
import org.jupnp.model.message.StreamResponseMessage;
import org.jupnp.model.message.UpnpHeaders;
import org.jupnp.model.message.UpnpMessage;
import org.jupnp.model.message.UpnpRequest;
import org.jupnp.model.message.header.HostHeader;
import org.jupnp.model.message.header.UpnpHeader;
import org.jupnp.protocol.ProtocolFactory;
import org.jupnp.transport.Router;
import org.jupnp.transport.spi.InitializationException;
import org.jupnp.transport.spi.StreamServer;
import org.jupnp.transport.spi.StreamServerConfiguration;
import org.jupnp.transport.spi.UpnpStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class SocketHttpStreamServer implements StreamServer<SocketHttpStreamServer.Configuration> {

    private static final int ACCEPT_BACKLOG = 50;
    private static final int BIND_ATTEMPTS = 5;
    private static final long BIND_RETRY_MS = 300L;
    private static final int MAX_WORKERS = 8;
    private static final int MAX_QUEUED_CONNECTIONS = 32;
    private static final int MAX_REQUEST_LINE_BYTES = 8 * 1024;
    private static final int MAX_HEADER_BYTES = 32 * 1024;
    private static final int MAX_HEADER_COUNT = 100;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    /** Short read timeout: stalled clients must not pin connection workers for 30s. */
    private static final int SOCKET_TIMEOUT_MS = 8_000;

    private final Configuration configuration;
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private ThreadPoolExecutor workers;
    private volatile boolean stopped;
    private Router router;

    public SocketHttpStreamServer(Configuration configuration) {
        this.configuration = configuration;
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public void init(InetAddress bindAddress, Router router) throws InitializationException {
        this.router = router;
        AtomicInteger workerId = new AtomicInteger();
        workers = new ThreadPoolExecutor(
                MAX_WORKERS,
                MAX_WORKERS,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_CONNECTIONS),
                runnable -> {
                    Thread thread = new Thread(runnable, "dlna-http-" + workerId.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        workers.allowCoreThreadTimeOut(true);
        try {
            serverSocket = bindWithRetry(bindAddress, configuration.getListenPort());
        } catch (BindException e) {
            if (!configuration.fallbackToEphemeral() || configuration.getListenPort() == 0) {
                workers.shutdownNow();
                throw new InitializationException("Could not bind HTTP server socket on " + bindAddress, e);
            }
            try {
                serverSocket = bind(bindAddress, 0);
            } catch (IOException fallbackError) {
                workers.shutdownNow();
                throw new InitializationException("Could not bind fallback HTTP server socket on " + bindAddress, fallbackError);
            }
        } catch (IOException e) {
            workers.shutdownNow();
            throw new InitializationException("Could not bind HTTP server socket on " + bindAddress, e);
        }
    }

    /**
     * Bind the configured port, retrying briefly before giving up.
     *
     * A renderer restart (a network change, or the settings toggle) destroys the old jUPnP instance
     * and starts a new one ~400ms later, but jUPnP tears the old transports down on a background
     * thread — so the previous listen socket can still be open when the new instance binds. Without
     * this retry the fixed port loses that race, the server silently falls back to a random port,
     * and every SSDP reply advertises a LOCATION that nothing serves: controllers then discover the
     * device and never manage to load its description, so it stays out of their device list.
     */
    private ServerSocket bindWithRetry(InetAddress address, int port) throws IOException {
        BindException last = null;
        for (int attempt = 0; attempt < BIND_ATTEMPTS; attempt++) {
            try {
                return bind(address, port);
            } catch (BindException e) {
                last = e;
                try {
                    Thread.sleep(BIND_RETRY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
        }
        throw last;
    }

    private ServerSocket bind(InetAddress address, int port) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        try {
            socket.bind(new InetSocketAddress(address, port), ACCEPT_BACKLOG);
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    @Override
    public int getPort() {
        return serverSocket != null ? serverSocket.getLocalPort() : -1;
    }

    @Override
    public void stop() {
        stopped = true;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Socket socket : activeSockets) closeQuietly(socket);
        activeSockets.clear();
        if (workers != null) workers.shutdownNow();
    }

    @Override
    public void run() {
        while (!stopped) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                activeSockets.add(socket);
                // A bounded pool prevents stalled or hostile LAN clients from creating an
                // unbounded number of threads while still allowing independent SOAP calls.
                try {
                    workers.execute(() -> {
                        try {
                            router.received(new SocketUpnpStream(router.getProtocolFactory(), socket, () -> activeSockets.remove(socket)));
                        } catch (RuntimeException e) {
                            activeSockets.remove(socket);
                            closeQuietly(socket);
                        }
                    });
                } catch (RuntimeException rejected) {
                    activeSockets.remove(socket);
                    closeQuietly(socket);
                }
            } catch (SocketException e) {
                break;
            } catch (IOException ignored) {
            }
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static class SocketUpnpStream extends UpnpStream {

        private final Socket socket;
        private final Runnable onClosed;

        SocketUpnpStream(ProtocolFactory protocolFactory, Socket socket, Runnable onClosed) {
            super(protocolFactory);
            this.socket = socket;
            this.onClosed = onClosed;
        }

        private String readLine(InputStream is, int maxBytes) throws IOException {
            StringBuilder sb = new StringBuilder();
            int prev = -1;
            int b;
            while ((b = is.read()) != -1) {
                if (prev == '\r' && b == '\n') {
                    sb.deleteCharAt(sb.length() - 1);
                    return sb.toString();
                }
                if (sb.length() >= maxBytes) throw new IOException("HTTP line too long");
                sb.append((char) b);
                prev = b;
            }
            return sb.toString();
        }

        private void writeStatusLine(OutputStream os, int code, String reason) throws IOException {
            os.write(("HTTP/1.1 " + code + " " + Objects.requireNonNullElse(reason, "") + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }

        private void writeHeader(OutputStream os, String name, String value) throws IOException {
            os.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        }

        private void writeEndHeaders(OutputStream os) throws IOException {
            os.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        }

        @Override
        public void run() {
            try {
                InputStream is = socket.getInputStream();
                String requestLine = readLine(is, MAX_REQUEST_LINE_BYTES);
                String[] parts = requestLine.split(" ", 3);
                if (requestLine.isEmpty() || parts.length < 2) {
                    socket.close();
                    return;
                }
                Map<String, List<String>> headers = readHeaders(is);
                StreamRequestMessage requestMessage = buildRequestMessage(parts[0], parts[1], headers);
                readBodyInto(is, requestMessage, headers);
                StreamResponseMessage responseMessage = process(requestMessage);
                OutputStream os = socket.getOutputStream();
                writeResponse(os, responseMessage);
                os.flush();
                responseSent(responseMessage);
            } catch (Exception e) {
                responseException(e);
            } finally {
                closeQuietly(socket);
                onClosed.run();
            }
        }

        private Map<String, List<String>> readHeaders(InputStream is) throws IOException {
            Map<String, List<String>> headers = new HashMap<>();
            int totalBytes = 0;
            int count = 0;
            String line;
            while (!(line = readLine(is, MAX_HEADER_BYTES)).isEmpty()) {
                totalBytes += line.length();
                if (totalBytes > MAX_HEADER_BYTES || ++count > MAX_HEADER_COUNT) throw new IOException("HTTP headers too large");
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                headers.computeIfAbsent(line.substring(0, colon).trim().toLowerCase(), k -> new ArrayList<>()).add(line.substring(colon + 1).trim());
            }
            return headers;
        }

        private StreamRequestMessage buildRequestMessage(String method, String rawUri, Map<String, List<String>> headers) {
            // jUPnP Registry.getResource() REJECTS absolute URIs and matches path+query only
            // (e.g. /dev/<udn>/desc). Keep the request-target relative.
            String target = rawUri == null ? "/" : rawUri.trim();
            int scheme = target.indexOf("://");
            if (scheme > 0) {
                int pathStart = target.indexOf('/', scheme + 3);
                target = pathStart < 0 ? "/" : target.substring(pathStart);
            }
            if (target.isEmpty()) target = "/";
            if (!target.startsWith("/")) target = "/" + target;
            StreamRequestMessage msg = new StreamRequestMessage(UpnpRequest.Method.getByHttpName(method), URI.create(target));
            msg.setConnection(new SocketConnection(socket));
            UpnpHeaders upnpHeaders = new UpnpHeaders();
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null || "host".equals(entry.getKey())) continue;
                for (String value : entry.getValue()) {
                    if (value != null) upnpHeaders.add(entry.getKey(), value);
                }
            }
            // ReceivingRetrieval requires a *typed* Host header. A raw multi-map "host"
            // key is not always parsed into HostHeader, which yields 412 and makes every
            // control point drop the MediaRenderer.
            List<String> hostValues = headers.get("host");
            String hostValue = (hostValues == null || hostValues.isEmpty()) ? null : hostValues.get(0);
            try {
                HostHeader hostHeader = new HostHeader();
                hostHeader.setString(hostValue != null && !hostValue.isEmpty()
                        ? hostValue
                        : socket.getLocalAddress().getHostAddress() + ":" + socket.getLocalPort());
                upnpHeaders.add(UpnpHeader.Type.HOST, hostHeader);
            } catch (Exception e) {
                upnpHeaders.add(UpnpHeader.Type.HOST, new HostHeader(socket.getLocalPort()));
            }
            msg.setHeaders(upnpHeaders);
            return msg;
        }

        private void readBodyInto(InputStream is, StreamRequestMessage msg, Map<String, List<String>> headers) throws IOException {
            List<String> length = headers.getOrDefault("content-length", List.of());
            if (length == null || length.isEmpty()) return;
            long declared = Long.parseLong(length.get(0).trim());
            if (declared <= 0) return;
            if (declared > MAX_BODY_BYTES) throw new IOException("HTTP body too large");
            int len = (int) declared;
            byte[] body = new byte[len];
            int offset = 0, read;
            while (offset < len && (read = is.read(body, offset, len - offset)) != -1) offset += read;
            if (offset < len) throw new IOException("Truncated HTTP body: expected " + len + ", got " + offset);
            if (msg.isContentTypeMissingOrText()) msg.setBodyCharacters(body);
            else msg.setBody(UpnpMessage.BodyType.BYTES, body);
        }

        private void writeResponse(OutputStream os, StreamResponseMessage msg) throws IOException {
            if (msg == null) {
                writeStatusLine(os, 404, "Not Found");
                writeHeader(os, "Content-Length", "0");
                writeEndHeaders(os);
                return;
            }
            writeStatusLine(os, msg.getOperation().getStatusCode(), msg.getOperation().getStatusMessage());
            for (Map.Entry<String, List<String>> e : msg.getHeaders().entrySet()) {
                if (e.getKey() == null || "content-length".equalsIgnoreCase(e.getKey())) continue;
                for (String v : e.getValue()) writeHeader(os, e.getKey(), v);
            }
            byte[] body = msg.hasBody() ? msg.getBodyBytes() : null;
            writeHeader(os, "Content-Length", String.valueOf(body != null ? body.length : 0));
            writeEndHeaders(os);
            if (body != null && body.length > 0) os.write(body);
        }
    }

    private record SocketConnection(Socket socket) implements Connection {

        @Override
        public boolean isOpen() {
            return !socket.isClosed();
        }

        @Override
        public InetAddress getRemoteAddress() {
            return socket.getInetAddress();
        }

        @Override
        public InetAddress getLocalAddress() {
            return socket.getLocalAddress();
        }
    }

    public record Configuration(int listenPort, boolean fallbackToEphemeral) implements StreamServerConfiguration {

        @Override
        public int getListenPort() {
            return listenPort;
        }
    }
}
