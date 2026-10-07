package com.ponie.dayov12;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import java.io.*;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

public final class FoxConfigStore {
    private static final String ALIAS = "fox.awg.configuration.v1";
    private static AtomicFile file(Context c) { return new AtomicFile(new File(c.getNoBackupFilesDir(),"amneziawg.aes")); }
    private static synchronized SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            gen.generateKey();
        }
        return (SecretKey)ks.getKey(ALIAS,null);
    }
    public static boolean exists(Context c) { return file(c).getBaseFile().exists(); }
    public static void save(Context c, byte[] plaintext) throws Exception {
        FoxAwgConfig.parse(plaintext);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv = cipher.getIV(), encrypted = cipher.doFinal(plaintext);
        AtomicFile target = file(c); FileOutputStream out = null;
        try { out=target.startWrite(); out.write(iv.length); out.write(iv); out.write(encrypted); target.finishWrite(out); }
        catch (Exception e) { if(out!=null) target.failWrite(out); throw e; }
        finally { Arrays.fill(encrypted,(byte)0); }
    }
    public static byte[] load(Context c) throws Exception {
        byte[] data;
        try(InputStream in=file(c).openRead()) { data=FoxAwgConfig.readLimited(in,32768+64); }
        if (data.length < 29 || (data[0]&255) != 12) throw new IOException("INVALID_ENCRYPTED_CONFIG");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Arrays.copyOfRange(data,1,13)));
        try { return cipher.doFinal(data,13,data.length-13); } finally { Arrays.fill(data,(byte)0); }
    }
}
