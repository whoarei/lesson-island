package top.mossmoss.lessonisland;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

final class Secrets {
  private final Context context;

  Secrets(Context c) {
    context = c;
  }

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias("textisland_api")) {
      KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      g.init(
          new KeyGenParameterSpec.Builder(
                  "textisland_api", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      g.generateKey();
    }
    return (SecretKey) ks.getKey("textisland_api", null);
  }

  void set(String value) throws Exception {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    context
        .getSharedPreferences("secure", 0)
        .edit()
        .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
        .putString(
            "secret",
            Base64.encodeToString(
                cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                Base64.NO_WRAP))
        .commit();
  }

  String get() throws Exception {
    android.content.SharedPreferences p = context.getSharedPreferences("secure", 0);
    if (!p.contains("secret")) return "";
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(p.getString("iv", ""), Base64.DEFAULT)));
    return new String(
        cipher.doFinal(Base64.decode(p.getString("secret", ""), Base64.DEFAULT)),
        java.nio.charset.StandardCharsets.UTF_8);
  }

  boolean hasPin() {
    return context.getSharedPreferences("secure", 0).contains("pin");
  }

  void setPin(String pin) {
    String salt = java.util.UUID.randomUUID().toString();
    context
        .getSharedPreferences("secure", 0)
        .edit()
        .putString("salt", salt)
        .putString("pin", Store.hash(salt + pin))
        .commit();
  }

  boolean checkPin(String pin) {
    android.content.SharedPreferences p = context.getSharedPreferences("secure", 0);
    return MessageDigest.isEqual(
        Store.hash(p.getString("salt", "") + pin).getBytes(), p.getString("pin", "").getBytes());
  }
}
