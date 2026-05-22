package mindustryX.features.ai;

import arc.util.*;
import arc.util.serialization.*;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.*;
import java.nio.file.*;

/**
 * File-backed shared ring buffer used as the high-frequency event channel.
 * A separate process can read the same file by polling header + slot data.
 */
public final class SharedEventRing implements Closeable{
    public static final int magic = 0x41494252; // AIBR
    public static final int version = 1;
    public static final int headerSize = 64;
    public static final int slotHeaderSize = 24;

    private static final Charset utf8 = StandardCharsets.UTF_8;

    private final Path file;
    private final int slotCount;
    private final int slotSize;

    private RandomAccessFile raf;
    private FileChannel channel;
    private MappedByteBuffer buffer;
    private boolean opened;
    private long writeSeq;
    private long dropped;

    public SharedEventRing(Path file, int slotCount, int slotSize){
        this.file = file;
        this.slotCount = Math.max(64, slotCount);
        this.slotSize = Math.max(256, slotSize);
    }

    public void open() throws IOException{
        if(opened) return;

        Files.createDirectories(file.getParent());

        long totalSize = headerSize + (long)slotCount * slotSize;
        raf = new RandomAccessFile(file.toFile(), "rw");
        raf.setLength(totalSize);
        channel = raf.getChannel();
        buffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalSize);
        buffer.order(ByteOrder.BIG_ENDIAN);

        writeSeq = 0L;
        dropped = 0L;
        writeHeader();
        opened = true;
    }

    public boolean available(){
        return opened;
    }

    public Path path(){
        return file;
    }

    public int slotCount(){
        return slotCount;
    }

    public int slotSize(){
        return slotSize;
    }

    public long writeSeq(){
        return writeSeq;
    }

    public long dropped(){
        return dropped;
    }

    public void append(String type, Jval payload){
        if(!opened || payload == null) return;

        byte[] bytes = payload.toString(Jval.Jformat.plain).getBytes(utf8);
        int maxPayload = slotSize - slotHeaderSize;
        int payloadLength = Math.min(bytes.length, maxPayload);

        long seq = writeSeq;
        int slot = (int)(seq % slotCount);
        int offset = headerSize + slot * slotSize;

        if(seq >= slotCount){
            dropped = Math.max(dropped, seq - slotCount + 1);
        }

        buffer.putLong(offset, seq);
        buffer.putInt(offset + 8, typeCode(type));
        buffer.putInt(offset + 12, payloadLength);
        buffer.putLong(offset + 16, Time.millis());

        buffer.position(offset + slotHeaderSize);
        buffer.put(bytes, 0, payloadLength);

        writeSeq = seq + 1L;
        writeHeader();
    }

    private void writeHeader(){
        if(buffer == null) return;

        buffer.putInt(0, magic);
        buffer.putInt(4, version);
        buffer.putInt(8, slotCount);
        buffer.putInt(12, slotSize);
        buffer.putLong(16, writeSeq);
        buffer.putLong(24, dropped);
        buffer.putLong(32, Time.millis());
    }

    private static int typeCode(String type){
        if("tile_changed".equals(type)) return 1;
        if("health_changed".equals(type)) return 2;
        if("packet_sent".equals(type)) return 3;
        return 0;
    }

    @Override
    public void close() throws IOException{
        if(!opened) return;
        try{
            if(buffer != null){
                writeHeader();
                buffer.force();
            }
        }finally{
            opened = false;
            if(channel != null) channel.close();
            if(raf != null) raf.close();
            channel = null;
            raf = null;
            buffer = null;
        }
    }
}
