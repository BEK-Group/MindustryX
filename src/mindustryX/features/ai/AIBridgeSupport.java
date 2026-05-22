package mindustryX.features.ai;

import arc.func.*;
import arc.struct.*;
import arc.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class AIBridgeSupport{
    static final long opResultTtlMs = 20_000L;
    static final long cursorTtlMs = 60_000L;
    static final int maxOpResults = 32_768;
    static final int defaultControlPort = 46491;
    static final int maxFramePayload = 1024 * 1024;
    static final long tickBudgetNanos = 1_000_000L;

    private AIBridgeSupport(){
    }

    enum ErrorCode{
        badRequest(1001, "bad_request"),
        notFound(1002, "not_found"),
        outOfBounds(1003, "out_of_bounds"),
        queueFull(1004, "queue_full"),
        readOnly(1005, "read_only"),
        cursorExpired(1006, "cursor_expired"),
        opExpired(1007, "op_expired"),
        internalError(1500, "internal_error");

        final int value;
        final String code;

        ErrorCode(int value, String code){
            this.value = value;
            this.code = code;
        }
    }

    static final class BridgeException extends RuntimeException{
        final ErrorCode errorCode;

        BridgeException(ErrorCode errorCode, String message){
            super(message);
            this.errorCode = errorCode;
        }
    }

    static final class ExpiringEntry<T>{
        final T value;
        final long expiresAt;

        ExpiringEntry(T value, long expiresAt){
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }

    static final class OpResult{
        final long opId;
        final boolean success;
        final String payload;
        final ErrorCode errorCode;
        final String message;
        final long completedAt;

        OpResult(long opId, boolean success, String payload, ErrorCode errorCode, String message){
            this.opId = opId;
            this.success = success;
            this.payload = payload;
            this.errorCode = errorCode;
            this.message = message;
            this.completedAt = Time.millis();
        }
    }

    static final class CursorView{
        final String token;
        final String type;
        final Seq<String> pages;
        final long createdAt = Time.millis();

        CursorView(String token, String type, Seq<String> pages){
            this.token = token;
            this.type = type;
            this.pages = pages;
        }
    }

    static final class TimedCache<K, V>{
        private final LinkedHashMap<K, ExpiringEntry<V>> map = new LinkedHashMap<>();
        private final long ttlMs;
        private final int capacity;

        TimedCache(long ttlMs, int capacity){
            this.ttlMs = ttlMs;
            this.capacity = capacity;
        }

        synchronized void put(K key, V value){
            cleanup();
            map.put(key, new ExpiringEntry<>(value, Time.millis() + ttlMs));
            trim();
        }

        synchronized V get(K key){
            ExpiringEntry<V> entry = map.get(key);
            if(entry == null) return null;
            if(entry.expiresAt < Time.millis()){
                map.remove(key);
                return null;
            }
            return entry.value;
        }

        synchronized V remove(K key){
            ExpiringEntry<V> entry = map.remove(key);
            return entry == null ? null : entry.value;
        }

        synchronized void cleanup(){
            long now = Time.millis();
            Iterator<Map.Entry<K, ExpiringEntry<V>>> iterator = map.entrySet().iterator();
            while(iterator.hasNext()){
                Map.Entry<K, ExpiringEntry<V>> entry = iterator.next();
                if(entry.getValue().expiresAt < now){
                    iterator.remove();
                }
            }
        }

        private void trim(){
            if(map.size() <= capacity) return;
            Iterator<K> iterator = map.keySet().iterator();
            while(map.size() > capacity && iterator.hasNext()){
                iterator.next();
                iterator.remove();
            }
        }
    }

    static final class SameTickCache<K, V>{
        private int tick = -1;
        private final ObjectMap<K, V> map = new ObjectMap<>();

        synchronized V getOrCompute(K key, Prov<V> provider){
            int currentTick = (int)Time.time;
            if(tick != currentTick){
                map.clear();
                tick = currentTick;
            }
            if(map.containsKey(key)) return map.get(key);
            V value = provider.get();
            map.put(key, value);
            return value;
        }
    }

    static final class Ids{
        private final AtomicLong ops = new AtomicLong(1L);

        long nextOpId(){
            return ops.getAndIncrement();
        }
    }

    static final class Queues{
        final BlockingQueue<QueuedRequest> writes = new ArrayBlockingQueue<>(4096);
        final BlockingQueue<QueuedRequest> reads = new ArrayBlockingQueue<>(4096);
    }

    static final class QueuedRequest{
        final AIProtocolServer.Connection connection;
        final AIProtocolServer.Message request;
        final boolean write;

        QueuedRequest(AIProtocolServer.Connection connection, AIProtocolServer.Message request, boolean write){
            this.connection = connection;
            this.request = request;
            this.write = write;
        }
    }
}
