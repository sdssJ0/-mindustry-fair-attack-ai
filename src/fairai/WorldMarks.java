package fairai;

import arc.struct.*;
import arc.util.serialization.Base64Coder;
import mindustry.Vars;
import mindustry.game.Rules;
import java.io.*;
import java.util.zip.*;

/** Compact spatial planning records. Vanilla saves encode the entire rules JSON using writeUTF. */
final class WorldMarks{
    private static Rules owner;
    private static StringMap values;
    private static final String tag="fair-ai.marks-v1";
    private static void load(){
        if(owner==Vars.state.rules && values!=null) return;
        owner=Vars.state.rules; values=new StringMap();
        String encoded=owner.tags.get(tag);
        if(encoded!=null) try(DataInputStream in=new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(Base64Coder.decode(encoded))))){
            int count=in.readInt();
            if(count<0 || count>1000000) throw new IOException("Invalid AI record count");
            for(int i=0;i<count;i++) values.put(in.readUTF(),in.readUTF());
        }catch(IOException | IllegalArgumentException failure){ throw new IllegalStateException("Cannot read AI planning records",failure); }
        //Migrate old saves without changing inventories or map tiles.
        Seq<String> legacy=new Seq<>();
        owner.tags.each((key,value)->{ if(spatial(key)){ values.put(key,value); legacy.add(key); } });
        for(String key:legacy) owner.tags.remove(key);
        if(legacy.any()) flush();
    }
    private static boolean spatial(String key){
        return key.startsWith("fair-ai.flow.") || key.startsWith("fair-ai.owned.") || key.startsWith("fair-ai.retired.") ||
            key.startsWith("fair-ai.reserve.") || key.startsWith("fair-ai.campus-flow.") || key.startsWith("fair-ai.core-mine.");
    }
    static String get(String key){ load(); return values.get(key); }
    static int getInt(String key,int fallback){ String v=get(key); try{return v==null ? fallback : Integer.parseInt(v);}catch(NumberFormatException e){return fallback;} }
    static void put(String key,String value){ load(); if(value.equals(values.get(key))) return; values.put(key,value); flush(); }
    static void remove(String key){ load(); if(values.remove(key)!=null) flush(); }
    static void each(arc.func.Cons2<String,String> action){ load(); values.each(action); }
    private static void flush(){
        try{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(DataOutputStream out=new DataOutputStream(new DeflaterOutputStream(bytes))){
                out.writeInt(values.size);
                for(ObjectMap.Entry<String,String> entry:values){ out.writeUTF(entry.key); out.writeUTF(entry.value); }
            }
            owner.tags.put(tag,new String(Base64Coder.encode(bytes.toByteArray())));
        }catch(IOException failure){throw new IllegalStateException("Cannot save AI planning records",failure);}
    }
}
