package com.bubble.bdshost;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Patches ONLY experiment flags in Minecraft Bedrock's little-endian NBT level.dat.
 * Every other world field (seed, identity, time, generator, user settings) stays untouched.
 * Never copies a foreign world level.dat.
 */
public final class WorldExperiments {
    private WorldExperiments() {}
    private static final int MAX_FILE = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 64;

    private static final String[] FLAGS = {
            "gametest", "data_driven_vanilla_blocks_and_items",
            "experiments_ever_used", "saved_with_toggled_experiments"
    };

    public static File selectedWorldLevel(Context context) throws IOException {
        File root = AppPaths.bds(context);
        File properties = new File(root, "server.properties");
        Properties p = new Properties();
        if (properties.isFile()) {
            try (InputStreamReader in = new InputStreamReader(
                    new FileInputStream(properties), StandardCharsets.UTF_8)) { p.load(in); }
        }
        String name = p.getProperty("level-name", "Meu Mundo Bubble").trim();
        if (name.isEmpty()) name = "Meu Mundo Bubble";
        // Same normalization used by the app's world configuration.
        name = name.replace('/', '_').replace('\\', '_');
        while (name.contains("..")) name = name.replace("..", "_");
        return new File(new File(new File(root, "worlds"), name), "level.dat");
    }

    public static boolean ensureDefaults(File level) throws IOException {
        if (!level.isFile()) throw new IOException("Mundo ainda não foi gerado: " + level.getName());
        byte[] original = Files.readAllBytes(level.toPath());
        byte[] edited = apply(original);
        if (edited == original) return false;

        File folder = level.getParentFile();
        String backupName = "level.dat.bubble-before-experiments-" + System.currentTimeMillis() + ".bak";
        File backup = new File(folder, backupName);
        Files.copy(level.toPath(), backup.toPath());

        File partial = new File(folder, "level.dat.bubble.tmp");
        try {
            try (FileOutputStream out = new FileOutputStream(partial)) {
                out.write(edited);
                out.getFD().sync();
            }
            // Don't overwrite the engine's own level.dat_old backup.
            Files.move(partial.toPath(), level.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            if (partial.exists()) partial.delete();
        }
        return true;
    }

    public static byte[] apply(byte[] data) throws IOException {
        if (data == null || data.length < 12 || data.length > MAX_FILE)
            throw new IOException("level.dat inválido ou grande demais");
        Reader r = new Reader(data);
        int formatVersion = r.readIntAt(0);
        int declaredLength = r.readIntAt(4);
        if (formatVersion < 1 || formatVersion > 100 || declaredLength != data.length - 8)
            throw new IOException("Cabeçalho de level.dat inválido");
        r.position = 8;
        if (r.byteValue() != 10) throw new IOException("NBT raiz não é Compound");
        r.skipString(); // root name

        int rootEnd = -1;
        Entry experiments = null;
        while (r.position < data.length) {
            int tagStart = r.position;
            int type = r.byteValue();
            if (type == 0) { rootEnd = tagStart; break; }
            String name = r.stringValue();
            int payloadStart = r.position;
            r.skip(type, 1);
            if ("experiments".equals(name)) {
                if (type != 10) throw new IOException("experiments tem tipo NBT inválido");
                experiments = new Entry(payloadStart, r.position);
            }
        }
        if (rootEnd < 0 || r.position != data.length)
            throw new IOException("NBT raiz truncado ou com dados inesperados");

        Map<Integer,Byte> rewrites = new HashMap<>();
        ByteArrayOutputStream extra = new ByteArrayOutputStream();
        int insertAt;
        if (experiments == null) {
            insertAt = rootEnd;
            extra.write(10);
            writeName(extra, "experiments");
            for (String key : FLAGS) writeByteTag(extra, key, 1);
            extra.write(0);
        } else {
            r.position = experiments.start;
            Map<String,Integer> found = new LinkedHashMap<>();
            int experimentsEnd = -1;
            while (r.position < experiments.end) {
                int tagStart = r.position;
                int type = r.byteValue();
                if (type == 0) { experimentsEnd = tagStart; break; }
                String name = r.stringValue();
                int payloadStart = r.position;
                r.skip(type, 2);
                for (String required : FLAGS) {
                    if (required.equals(name)) {
                        if (type != 1) throw new IOException("Flag " + required + " não é NBT Byte");
                        found.put(name,payloadStart);
                        if (data[payloadStart] != 1) rewrites.put(payloadStart,(byte)1);
                    }
                }
            }
            if (experimentsEnd < 0 || r.position != experiments.end)
                throw new IOException("Compound experiments inválido");
            insertAt = experimentsEnd;
            for (String key : FLAGS) {
                if (!found.containsKey(key)) writeByteTag(extra,key,1);
            }
        }
        if (rewrites.isEmpty() && extra.size() == 0) return data;
        byte[] changed = data.clone();
        for (Map.Entry<Integer,Byte> e : rewrites.entrySet()) changed[e.getKey()] = e.getValue();
        byte[] added = extra.toByteArray();
        byte[] result = new byte[changed.length + added.length];
        System.arraycopy(changed,0,result,0,insertAt);
        System.arraycopy(added,0,result,insertAt,added.length);
        System.arraycopy(changed,insertAt,result,insertAt + added.length,changed.length - insertAt);
        writeIntAt(result,4,result.length-8);
        // Validate the output with the same parser, including exact payload length.
        new Reader(result).checkRoot();
        return result;
    }

    private static void writeName(ByteArrayOutputStream out, String key) throws IOException {
        byte[] encoded = key.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 65535) throw new IOException("Nome de flag grande demais");
        out.write(encoded.length & 255);
        out.write((encoded.length >>> 8) & 255);
        out.write(encoded);
    }
    private static void writeByteTag(ByteArrayOutputStream out,String name,int value) throws IOException {
        out.write(1);
        writeName(out,name);
        out.write(value);
    }
    private static void writeIntAt(byte[] b,int p,int v) {
        b[p]=(byte)v; b[p+1]=(byte)(v>>>8); b[p+2]=(byte)(v>>>16); b[p+3]=(byte)(v>>>24);
    }
    private static final class Entry {
        final int start,end;
        Entry(int start,int end) { this.start=start; this.end=end; }
    }
    private static final class Reader {
        final byte[] b;
        int position;
        Reader(byte[] b) { this.b=b; }
        void require(int n) throws IOException {
            if (n < 0 || (long)position + n > b.length) throw new IOException("NBT truncado");
        }
        int byteValue() throws IOException { require(1); return b[position++] & 255; }
        int readUShort() throws IOException {
            require(2);
            int v=(b[position]&255) | ((b[position+1]&255)<<8);
            position+=2;
            return v;
        }
        int readInt() throws IOException { require(4);int v=readIntAt(position);position+=4;return v; }
        int readIntAt(int p) throws IOException {
            if (p<0 || p+4>b.length) throw new IOException("NBT truncado");
            return (b[p]&255) | ((b[p+1]&255)<<8) | ((b[p+2]&255)<<16) | ((b[p+3]&255)<<24);
        }
        String stringValue() throws IOException {
            int len=readUShort();require(len);
            String s=new String(b,position,len,StandardCharsets.UTF_8);
            position+=len;return s;
        }
        void skipString() throws IOException {int len=readUShort();require(len);position+=len;}
        void jump(long n) throws IOException {if (n<0 || n>Integer.MAX_VALUE) throw new IOException("NBT inválido");require((int)n);position+=(int)n;}
        void skip(int type,int depth) throws IOException {
            if (depth>MAX_DEPTH) throw new IOException("NBT aninhado demais");
            switch(type) {
                case 1:jump(1);return;
                case 2:jump(2);return;
                case 3:case 5:jump(4);return;
                case 4:case 6:jump(8);return;
                case 7: {int n=readInt();jump(n);return;}
                case 8:skipString();return;
                case 9: {
                    int elemType=byteValue();int count=readInt();
                    if (count<0 || count>1_000_000 || (elemType==0&&count!=0))throw new IOException("Lista NBT inválida");
                    for(int i=0;i<count;i++)skip(elemType,depth+1);
                    return;
                }
                case 10: {
                    for(;;){int inner=byteValue();if(inner==0)return;skipString();skip(inner,depth+1);}
                }
                case 11: {int count=readInt();jump((long)count*4);return;}
                case 12: {int count=readInt();jump((long)count*8);return;}
                default:throw new IOException("Tipo NBT inválido: " + type);
            }
        }
        void checkRoot() throws IOException {
            if (b.length<12 || readIntAt(4)!=b.length-8)throw new IOException("Tamanho NBT inválido");
            position=8;if(byteValue()!=10)throw new IOException("Raiz NBT inválida");
            skipString();skip(10,0);
            if(position!=b.length)throw new IOException("Bytes inesperados no NBT");
        }
    }
}
