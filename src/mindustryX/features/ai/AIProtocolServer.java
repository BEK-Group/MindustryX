package mindustryX.features.ai;

import arc.util.*;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;

public final class AIProtocolServer implements Closeable{
    public interface RequestListener{
        void onRequest(Connection connection, Message message);
    }

    private static final Charset utf8 = StandardCharsets.UTF_8;

    private final RequestListener listener;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final Object lifecycleLock = new Object();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public AIProtocolServer(RequestListener listener){
        this.listener = listener;
    }

    public void start(int port) throws IOException{
        synchronized(lifecycleLock){
            if(running) return;

            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port));
            running = true;

            acceptThread = new Thread(this::acceptLoop, "MindustryX-AIBridge-Accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        }
    }

    public int port(){
        return serverSocket == null ? -1 : serverSocket.getLocalPort();
    }

    public boolean running(){
        return running;
    }

    public void broadcast(Message message){
        if(message == null) return;

        for(Connection connection : connections){
            connection.send(message);
        }
    }

    private void acceptLoop(){
        while(running){
            try{
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                Connection connection = new Connection(socket);
                connections.add(connection);
                connection.start();
            }catch(SocketException ignored){
                break;
            }catch(Throwable error){
                Log.err("AI bridge accept loop failed", error);
            }
        }
    }

    @Override
    public void close(){
        synchronized(lifecycleLock){
            running = false;

            if(serverSocket != null){
                try{
                    serverSocket.close();
                }catch(IOException ignored){
                }
            }

            for(Connection connection : new ArrayList<>(connections)){
                connection.close();
            }

            connections.clear();
        }
    }

    public final class Connection implements Closeable{
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final BlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();

        private volatile boolean closed;
        private Thread readerThread;
        private Thread writerThread;

        private Connection(Socket socket) throws IOException{
            this.socket = socket;
            this.input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            this.output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        public void start(){
            readerThread = new Thread(this::readerLoop, "MindustryX-AIBridge-Reader-" + socket.getPort());
            readerThread.setDaemon(true);
            readerThread.start();

            writerThread = new Thread(this::writerLoop, "MindustryX-AIBridge-Writer-" + socket.getPort());
            writerThread.setDaemon(true);
            writerThread.start();
        }

        public void send(Message message){
            if(closed || message == null) return;
            outbox.offer(encode(message));
        }

        private void readerLoop(){
            try{
                while(!closed){
                    Message message = decode(input);
                    if(message == null) break;
                    if(message.kind == null || message.kind.isEmpty()) message.kind = "request";
                    if("request".equals(message.kind)){
                        listener.onRequest(this, message);
                    }
                }
            }catch(EOFException ignored){
            }catch(SocketException ignored){
            }catch(Throwable error){
                if(!closed) Log.err("AI bridge reader failed", error);
            }finally{
                close();
            }
        }

        private void writerLoop(){
            try{
                while(!closed){
                    byte[] bytes = outbox.take();
                    if(bytes.length == 0) break;
                    output.write(bytes);
                    output.flush();
                }
            }catch(InterruptedException ignored){
                Thread.currentThread().interrupt();
            }catch(SocketException ignored){
            }catch(Throwable error){
                if(!closed) Log.err("AI bridge writer failed", error);
            }finally{
                close();
            }
        }

        @Override
        public void close(){
            if(closed) return;
            closed = true;
            connections.remove(this);
            outbox.offer(new byte[0]);
            try{
                socket.close();
            }catch(IOException ignored){
            }
        }
    }

    public static final class Message{
        public String kind;
        public long requestId;
        public String op;
        public String payload;
        public boolean accepted;
        public long opId = -1L;
        public String cursor;
        public int errorCode;
        public String errorName;
        public String message;
        public String eventType;
        public long eventSeq = -1L;
        public long dropped = -1L;

        public static Message request(long requestId, String op, String payload){
            Message message = new Message();
            message.kind = "request";
            message.requestId = requestId;
            message.op = op;
            message.payload = payload;
            return message;
        }

        public static Message response(long requestId){
            Message message = new Message();
            message.kind = "response";
            message.requestId = requestId;
            return message;
        }

        public static Message event(String eventType, String payload){
            Message message = new Message();
            message.kind = "event";
            message.eventType = eventType;
            message.payload = payload;
            return message;
        }
    }

    private static byte[] encode(Message message){
        try{
            ByteArrayOutputStream frameBuffer = new ByteArrayOutputStream(256);
            DataOutputStream frame = new DataOutputStream(frameBuffer);

            writeString(frame, 1, message.kind);
            writeLong(frame, 2, message.requestId);
            writeString(frame, 3, message.op);
            writeString(frame, 4, message.payload);
            writeBoolean(frame, 5, message.accepted);
            if(message.opId >= 0L) writeLong(frame, 6, message.opId);
            writeString(frame, 7, message.cursor);
            if(message.errorCode != 0) writeInt(frame, 8, message.errorCode);
            writeString(frame, 9, message.errorName);
            writeString(frame, 10, message.message);
            writeString(frame, 11, message.eventType);
            if(message.eventSeq >= 0L) writeLong(frame, 12, message.eventSeq);
            if(message.dropped >= 0L) writeLong(frame, 13, message.dropped);

            frame.flush();

            byte[] body = frameBuffer.toByteArray();
            ByteArrayOutputStream out = new ByteArrayOutputStream(body.length + 4);
            DataOutputStream data = new DataOutputStream(out);
            data.writeInt(body.length);
            data.write(body);
            data.flush();
            return out.toByteArray();
        }catch(IOException exception){
            throw new RuntimeException(exception);
        }
    }

    private static Message decode(DataInputStream input) throws IOException{
        int frameSize = input.readInt();
        if(frameSize <= 0 || frameSize > 8 * 1024 * 1024){
            throw new IOException("Invalid frame size: " + frameSize);
        }

        byte[] body = new byte[frameSize];
        input.readFully(body);

        DataInputStream data = new DataInputStream(new ByteArrayInputStream(body));
        Message message = new Message();

        while(data.available() > 0){
            int tag = data.readInt();
            byte type = data.readByte();
            int length = data.readInt();
            if(length < 0 || length > frameSize){
                throw new IOException("Invalid TLV length: " + length);
            }

            byte[] value = new byte[length];
            data.readFully(value);

            switch(tag){
                case 1:
                    message.kind = readString(type, value);
                    break;
                case 2:
                    message.requestId = readLong(type, value);
                    break;
                case 3:
                    message.op = readString(type, value);
                    break;
                case 4:
                    message.payload = readString(type, value);
                    break;
                case 5:
                    message.accepted = readBoolean(type, value);
                    break;
                case 6:
                    message.opId = readLong(type, value);
                    break;
                case 7:
                    message.cursor = readString(type, value);
                    break;
                case 8:
                    message.errorCode = readInt(type, value);
                    break;
                case 9:
                    message.errorName = readString(type, value);
                    break;
                case 10:
                    message.message = readString(type, value);
                    break;
                case 11:
                    message.eventType = readString(type, value);
                    break;
                case 12:
                    message.eventSeq = readLong(type, value);
                    break;
                case 13:
                    message.dropped = readLong(type, value);
                    break;
                default:
                    // forward-compatible skip
                    break;
            }
        }

        return message;
    }

    private static void writeField(DataOutputStream out, int tag, byte type, byte[] value) throws IOException{
        out.writeInt(tag);
        out.writeByte(type);
        out.writeInt(value.length);
        out.write(value);
    }

    private static void writeString(DataOutputStream out, int tag, String value) throws IOException{
        if(value == null) return;
        writeField(out, tag, (byte)1, value.getBytes(utf8));
    }

    private static void writeInt(DataOutputStream out, int tag, int value) throws IOException{
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(value);
        writeField(out, tag, (byte)2, buffer.array());
    }

    private static void writeLong(DataOutputStream out, int tag, long value) throws IOException{
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.putLong(value);
        writeField(out, tag, (byte)3, buffer.array());
    }

    private static void writeBoolean(DataOutputStream out, int tag, boolean value) throws IOException{
        writeField(out, tag, (byte)4, new byte[]{(byte)(value ? 1 : 0)});
    }

    private static String readString(byte type, byte[] value) throws IOException{
        if(type != 1) throw new IOException("Expected string field, found type=" + type);
        return new String(value, utf8);
    }

    private static int readInt(byte type, byte[] value) throws IOException{
        if(type != 2 || value.length != 4) throw new IOException("Expected int field");
        return ByteBuffer.wrap(value).getInt();
    }

    private static long readLong(byte type, byte[] value) throws IOException{
        if(type != 3 || value.length != 8) throw new IOException("Expected long field");
        return ByteBuffer.wrap(value).getLong();
    }

    private static boolean readBoolean(byte type, byte[] value) throws IOException{
        if(type != 4 || value.length != 1) throw new IOException("Expected boolean field");
        return value[0] != 0;
    }
}
