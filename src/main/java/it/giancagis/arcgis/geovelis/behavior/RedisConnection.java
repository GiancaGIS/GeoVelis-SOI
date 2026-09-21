package it.giancagis.arcgis.geovelis.behavior;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Small RESP2 transport for a standalone Redis endpoint, with bounded replies and socket timeouts. */
final class RedisConnection implements AutoCloseable {
    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;

    RedisConnection(BehaviorConfig config) throws IOException {
        this(config, 1000);
    }
    RedisConnection(BehaviorConfig config, int timeoutMs) throws IOException {
        socket = "rediss".equals(config.endpoint().getScheme())
                ? SSLSocketFactory.getDefault().createSocket() : new Socket();
        try {
            socket.connect(new InetSocketAddress(config.endpoint().getHost(),
                    config.endpoint().getPort() < 0 ? 6379 : config.endpoint().getPort()), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            if (socket instanceof SSLSocket) {
                SSLParameters params = ((SSLSocket) socket).getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                ((SSLSocket) socket).setSSLParameters(params);
                ((SSLSocket) socket).startHandshake();
            }
            input = new BufferedInputStream(socket.getInputStream());
            output = new BufferedOutputStream(socket.getOutputStream());
            if (!config.password().isEmpty()) {
                if (config.username().isEmpty()) command("AUTH", config.password());
                else command("AUTH", config.username(), config.password());
            }
        } catch (IOException | RuntimeException ex) {
            socket.close();
            throw ex;
        }
    }
    Object command(String... args) throws IOException {
        output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] value = arg.getBytes(StandardCharsets.UTF_8);
            output.write(("$" + value.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(value);
            output.write('\r'); output.write('\n');
        }
        output.flush();
        return read(0);
    }
    private Object read(int depth) throws IOException {
        if (depth > 4) throw new IOException("Redis reply nesting limit");
        int type = input.read();
        if (type == -1) throw new EOFException("Redis connection closed");
        String line = line();
        switch (type) {
            case '+': return line;
            case '-':
                if (line.startsWith("NOSCRIPT ")) throw new MissingScriptException();
                throw new IOException("Redis command rejected"); // Never expose server text or credentials.
            case ':': return integer(line);
            case '$': {
                int size = length(line, 65536);
                if (size == -1) return null;
                byte[] bytes = input.readNBytes(size);
                if (bytes.length != size || input.read() != '\r' || input.read() != '\n')
                    throw new IOException("Truncated Redis bulk reply");
                return new String(bytes, StandardCharsets.UTF_8);
            }
            case '*': {
                int size = length(line, 128);
                if (size == -1) return null;
                List<Object> values = new ArrayList<>(size);
                for (int i = 0; i < size; i++) values.add(read(depth + 1));
                return values;
            }
            default: throw new IOException("Unsupported Redis reply");
        }
    }
    private static long integer(String value) throws IOException {
        try { return Long.parseLong(value); }
        catch (NumberFormatException ex) { throw new IOException("Invalid Redis integer"); }
    }
    private static int length(String value, int max) throws IOException {
        long size = integer(value);
        if (size < -1 || size > max) throw new IOException("Redis reply size limit");
        return (int) size;
    }
    private String line() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (buffer.size() < 4096) {
            int b = input.read();
            if (b < 0) throw new EOFException("Redis connection closed");
            if (b == '\r') {
                if (input.read() != '\n') throw new IOException("Invalid Redis line");
                return buffer.toString(StandardCharsets.UTF_8.name());
            }
            buffer.write(b);
        }
        throw new IOException("Redis line size limit");
    }
    @Override public void close() throws IOException { socket.close(); }

    static final class MissingScriptException extends IOException {
        private static final long serialVersionUID = 1L;
        MissingScriptException() { super("Redis script cache miss"); }
    }
}
