package app.suiyi.notes;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class WidgetNoteStore {
    static final class Preview {
        final String id, title, body;
        Preview(String id, String title, String body) { this.id=id; this.title=title; this.body=body; }
    }

    static Preview latest(Context context) {
        SharedPreferences prefs=context.getSharedPreferences("suiyi",Context.MODE_PRIVATE);
        if(!prefs.getString("lockHash","").isEmpty()) return null;
        try {
            String encrypted=prefs.getString("secureData",null);
            if(encrypted==null) return null;
            JSONObject envelope=new JSONObject(encrypted);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,storageKey(),new GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));
            JSONObject data=new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
            JSONArray notes=data.optJSONArray("notes");
            if(notes==null||notes.length()==0) return null;
            JSONObject latest=notes.getJSONObject(0);
            for(int i=1;i<notes.length();i++) if(notes.getJSONObject(i).optLong("updatedAt")>latest.optLong("updatedAt")) latest=notes.getJSONObject(i);
            StringBuilder body=new StringBuilder(latest.optString("text",""));
            JSONArray blocks=latest.optJSONArray("blocks");
            if(blocks!=null) for(int i=0;i<blocks.length();i++){String text=blocks.getJSONObject(i).optString("text","").trim();if(!text.isEmpty()){if(body.length()>0)body.append(' ');body.append(text);}}
            String preview=body.toString().replace('\n',' ').trim();
            if(preview.length()>100)preview=preview.substring(0,100)+"…";
            String title=latest.optString("title","").trim();
            if(title.isEmpty()) title="無標題";
            return new Preview(latest.optString("id",""),title,preview);
        } catch(Exception ignored) { return null; }
    }

    private static SecretKey storageKey() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        return (SecretKey)store.getKey("suiyi_data_key_v1",null);
    }
}
