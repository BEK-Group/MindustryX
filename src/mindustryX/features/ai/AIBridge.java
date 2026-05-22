package mindustryX.features.ai;

import arc.files.*;
import arc.struct.*;
import arc.util.*;
import arc.util.serialization.*;
import mindustry.*;

import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static mindustryX.features.ai.AIBridgeSupport.*;

public final class AIBridge{
    private static final AIBridgeImpl impl = new AIBridgeImpl();

    private AIBridge(){
    }

    public static void init(){
        impl.init();
    }

    public static void update(){
        impl.update();
    }

    private static final class AIBridgeImpl implements AIProtocolServer.RequestListener{
        private final Queues queues = new Queues();
        private final Ids ids = new Ids();
        private final TimedCache<Long, OpResult> opResults = new TimedCache<>(opResultTtlMs, maxOpResults);
        private final TimedCache<String, CursorView> cursorCache = new TimedCache<>(cursorTtlMs, 2048);
        private final StateReader stateReader = new StateReader();
        private final ActionExecutor actionExecutor = new ActionExecutor(ids, opResults);
        private final Set<Long> pendingOps = ConcurrentHashMap.newKeySet();

        private AIProtocolServer protocolServer;
        private SharedEventRing sharedRing;
        private EventBridge eventBridge;
        private boolean initialized;
        private boolean degradedSharedMemory;
        private Path bridgeDir;
        private long lastCleanup;

        void init(){
            if(initialized) return;
            initialized = true;

            try{
                bridgeDir = initBridgeDir();
                sharedRing = initSharedRing(bridgeDir);
                protocolServer = new AIProtocolServer(this);
                protocolServer.start(defaultControlPort);
                eventBridge = new EventBridge(sharedRing, protocolServer);
                eventBridge.init();
                writeBridgeInfo();
                Log.infoTag("MindustryX-AIBridge", "Bridge started on tcp://127.0.0.1:" + protocolServer.port());
            }catch(Throwable error){
                Log.err("Failed to initialize AI bridge", error);
                degradeSharedMemory(error);
            }
        }

        void update(){
            if(!initialized) return;

            long start = System.nanoTime();
            boolean servedRead = false;

            while(System.nanoTime() - start < tickBudgetNanos){
                QueuedRequest write = queues.writes.poll();
                if(write != null){
                    handleWrite(write);
                    continue;
                }

                if(!servedRead){
                    QueuedRequest read = queues.reads.poll();
                    if(read != null){
                        handleRead(read);
                        servedRead = true;
                        continue;
                    }
                }

                break;
            }

            long now = Time.millis();
            if(now - lastCleanup >= 1000L){
                lastCleanup = now;
                opResults.cleanup();
                cursorCache.cleanup();
                if(protocolServer != null && protocolServer.running()){
                    writeBridgeInfo();
                }
            }
        }

        @Override
        public void onRequest(AIProtocolServer.Connection connection, AIProtocolServer.Message request){
            try{
                if(request.op == null || request.op.isEmpty()){
                    throw new BridgeException(ErrorCode.badRequest, "Missing op");
                }

                if("await".equals(request.op)){
                    handleAwait(connection, request);
                    return;
                }
                if("cursor_next".equals(request.op)){
                    handleCursorNext(connection, request);
                    return;
                }

                boolean write = isWrite(request.op);
                QueuedRequest queued = new QueuedRequest(connection, request, write);
                boolean offered = write ? queues.writes.offer(queued) : queues.reads.offer(queued);
                if(!offered){
                    throw new BridgeException(ErrorCode.queueFull, (write ? "Write" : "Read") + " queue is full");
                }

                if(write){
                    long opId = actionExecutor.enqueueOpId();
                    request.opId = opId;
                    pendingOps.add(opId);
                    AIProtocolServer.Message response = AIProtocolServer.Message.response(request.requestId);
                    response.accepted = true;
                    response.opId = opId;
                    response.message = "accepted";
                    connection.send(response);
                }
            }catch(BridgeException exception){
                sendError(connection, request.requestId, exception.errorCode, exception.getMessage());
            }catch(Throwable error){
                sendError(connection, request.requestId, ErrorCode.internalError, error.toString());
            }
        }

        private void handleRead(QueuedRequest queued){
            try{
                Jval request = parsePayload(queued.request.payload);
                String payload = stateReader.read(queued.request.op, request);
                sendPagedPayload(queued.connection, queued.request.requestId, queued.request.op, payload);
            }catch(BridgeException exception){
                sendError(queued.connection, queued.request.requestId, exception.errorCode, exception.getMessage());
            }catch(Throwable error){
                sendError(queued.connection, queued.request.requestId, ErrorCode.internalError, error.toString());
            }
        }

        private void handleWrite(QueuedRequest queued){
            try{
                Jval request = parsePayload(queued.request.payload);
                actionExecutor.execute(queued.request.opId, queued.request.op, request);
            }catch(Throwable error){
                OpResult failed = new OpResult(queued.request.opId, false, null, ErrorCode.internalError, error.toString());
                opResults.put(queued.request.opId, failed);
            }finally{
                pendingOps.remove(queued.request.opId);
            }
        }

        private void handleAwait(AIProtocolServer.Connection connection, AIProtocolServer.Message request){
            Jval payload = parsePayload(request.payload);
            long opId = payload.getLong("opId", -1L);
            if(opId < 0L){
                throw new BridgeException(ErrorCode.badRequest, "Missing opId");
            }

            OpResult result = actionExecutor.getResult(opId);
            if(result == null && pendingOps.contains(opId)){
                int timeoutMs = Math.max(0, Math.min(payload.getInt("timeoutMs", 5000), 30_000));
                long deadline = Time.millis() + timeoutMs;
                while((result = actionExecutor.getResult(opId)) == null && pendingOps.contains(opId) && Time.millis() < deadline){
                    Threads.sleep(5);
                }
                if(result == null){
                    result = actionExecutor.getResult(opId);
                }
            }

            if(result == null){
                throw new BridgeException(ErrorCode.opExpired, "Operation result not found");
            }

            AIProtocolServer.Message response = AIProtocolServer.Message.response(request.requestId);
            response.opId = result.opId;
            response.accepted = result.success;
            if(result.success){
                response.payload = result.payload;
                response.message = "completed";
            }else{
                response.errorCode = result.errorCode.value;
                response.errorName = result.errorCode.code;
                response.message = result.message;
            }
            connection.send(response);
        }

        private void handleCursorNext(AIProtocolServer.Connection connection, AIProtocolServer.Message request){
            Jval payload = parsePayload(request.payload);
            String token = payload.getString("cursor", "");
            CursorView view = cursorCache.get(token);
            if(view == null){
                throw new BridgeException(ErrorCode.cursorExpired, "Cursor expired");
            }

            int index = payload.getInt("index", 1);
            if(index < 0 || index >= view.pages.size){
                throw new BridgeException(ErrorCode.badRequest, "Cursor index out of range");
            }

            AIProtocolServer.Message response = AIProtocolServer.Message.response(request.requestId);
            response.cursor = index + 1 < view.pages.size ? token : "";
            response.payload = view.pages.get(index);
            response.accepted = true;
            connection.send(response);

            if(index + 1 >= view.pages.size){
                cursorCache.remove(token);
            }
        }

        private void sendPagedPayload(AIProtocolServer.Connection connection, long requestId, String type, String payload){
            if(payload == null) payload = "{}";
            byte[] bytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            if(bytes.length <= maxFramePayload){
                AIProtocolServer.Message response = AIProtocolServer.Message.response(requestId);
                response.accepted = true;
                response.payload = payload;
                connection.send(response);
                return;
            }

            Seq<String> pages = splitPayload(payload);
            String token = UUID.randomUUID().toString();
            cursorCache.put(token, new CursorView(token, type, pages));

            AIProtocolServer.Message response = AIProtocolServer.Message.response(requestId);
            response.accepted = true;
            response.cursor = token;
            response.payload = pages.first();
            response.message = "paged";
            connection.send(response);
        }

        private Seq<String> splitPayload(String payload){
            Seq<String> pages = new Seq<>();
            int maxChars = Math.max(16_384, maxFramePayload / 2);
            for(int from = 0; from < payload.length(); from += maxChars){
                pages.add(payload.substring(from, Math.min(payload.length(), from + maxChars)));
            }
            return pages;
        }

        private void sendError(AIProtocolServer.Connection connection, long requestId, ErrorCode code, String message){
            AIProtocolServer.Message response = AIProtocolServer.Message.response(requestId);
            response.accepted = false;
            response.errorCode = code.value;
            response.errorName = code.code;
            response.message = message;
            connection.send(response);
        }

        private Jval parsePayload(String payload){
            if(payload == null || payload.isEmpty()) return Jval.newObject();
            Jval value = Jval.read(payload);
            return value.isObject() ? value : Jval.newObject().put("value", value);
        }

        private boolean isWrite(String op){
            return switch(op){
                case "load_map", "join_game", "leave_game" -> true;
                case "place_block", "break_block", "write_logic", "spawn_unit", "set_speed", "pause" -> true;
                default -> false;
            };
        }

        private void writeBridgeInfo(){
            if(bridgeDir == null || protocolServer == null) return;

            Jval info = Jval.newObject()
                .put("version", 1)
                .put("host", "127.0.0.1")
                .put("port", protocolServer.port())
                .put("sharedRingPath", sharedRing == null ? "" : sharedRing.path().toString())
                .put("sharedRingEnabled", sharedRing != null && sharedRing.available())
                .put("sharedRingDropped", sharedRing == null ? 0L : sharedRing.dropped())
                .put("sharedRingWriteSeq", sharedRing == null ? 0L : sharedRing.writeSeq())
                .put("degradedSharedMemory", degradedSharedMemory)
                .put("generatedAt", Time.millis());

            try{
                Files.write(bridgeDir.resolve("bridge-info.json"), info.toString(Jval.Jformat.plain).getBytes(StandardCharsets.UTF_8));
            }catch(IOException error){
                Log.err("Failed to write bridge info", error);
            }
        }

        private void degradeSharedMemory(Throwable error){
            degradedSharedMemory = true;
            Log.warn("AI bridge shared-memory path degraded: " + error);
            if(sharedRing != null){
                try{
                    sharedRing.close();
                }catch(IOException ignored){
                }
                sharedRing = null;
            }
            if(protocolServer == null){
                try{
                    protocolServer = new AIProtocolServer(this);
                    protocolServer.start(defaultControlPort);
                    if(eventBridge == null){
                        eventBridge = new EventBridge(null, protocolServer);
                        eventBridge.init();
                    }
                }catch(IOException ioException){
                    Log.err("Failed to start degraded AI bridge", ioException);
                }
            }
            writeBridgeInfo();
        }

        private Path initBridgeDir() throws IOException{
            String temp = System.getProperty("java.io.tmpdir");
            Path dir = Paths.get(temp, "mindustryx-ai-bridge");
            Files.createDirectories(dir);
            return dir;
        }

        private SharedEventRing initSharedRing(Path dir) throws IOException{
            SharedEventRing ring = new SharedEventRing(dir.resolve("event-ring.bin"), 4096, 1024);
            ring.open();
            return ring;
        }
    }
}
