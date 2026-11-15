package top.mossmoss.lessonisland;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Locale;
import org.json.JSONObject;

/** Update boundaries tested on a dedicated emulator without opening an installer or network. */
public class UpdateFlowTest extends InstrumentationTestCase {
  String digest(File file) throws Exception {
    MessageDigest hash = MessageDigest.getInstance("SHA-256");
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[32768];
      int read;
      while ((read = input.read(buffer)) != -1) hash.update(buffer, 0, read);
    }
    StringBuilder value = new StringBuilder();
    for (byte b : hash.digest()) value.append(String.format(Locale.ROOT, "%02x", b & 255));
    return value.toString();
  }

  public void testUpdateProviderSharesOneFileReadOnly() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    File directory = new File(context.getCacheDir(), "updates");
    directory.mkdirs();
    File apk = new File(directory, "update.apk");
    byte[] before = apk.isFile() ? Files.readAllBytes(apk.toPath()) : null;
    Uri uri = UpdateApkProvider.uri(context);
    try {
      Files.write(apk.toPath(), "test-only public APK bytes".getBytes(StandardCharsets.UTF_8));
      try (InputStream input = context.getContentResolver().openInputStream(uri)) {
        assertNotNull(input);
        assertEquals('t', input.read());
      }
      try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
      }
      try {
        context.getContentResolver().openFileDescriptor(uri, "w");
        fail("The installer must never receive write access");
      } catch (FileNotFoundException | SecurityException expected) { }
      Uri wrong = uri.buildUpon().appendPath("state.json").build();
      try {
        context.getContentResolver().openInputStream(wrong);
        fail("Only the fixed update APK may be shared");
      } catch (FileNotFoundException | SecurityException expected) { }
    } finally {
      if (before == null) Files.deleteIfExists(apk.toPath());
      else Files.write(apk.toPath(), before);
    }
  }

  public void testCurrentVersionCannotBeOfferedAsAnUpgrade() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    AppUpdater updater = new AppUpdater(context);
    android.content.pm.PackageInfo current = updater.installed();
    File apk = new File(context.getApplicationInfo().sourceDir);
    UpdateRelease info = new UpdateRelease(current.versionName, AppUpdater.code(current), "test",
        "https://example.com/app.apk", apk.length(), digest(apk), 26);
    try {
      updater.verify(apk, info);
      fail("Installing the current or an older version must be refused");
    } catch (Exception expected) {
      assertEquals("此安装包不是更新版本", expected.getMessage());
    }
  }

  public void testWrongPackageCannotPassMetadataValidation() throws Exception {
    Context target = getInstrumentation().getTargetContext();
    AppUpdater updater = new AppUpdater(target);
    File testApk = new File(getInstrumentation().getContext().getApplicationInfo().sourceDir);
    UpdateRelease info = new UpdateRelease("0.3.1", AppUpdater.code(updater.installed()) + 1, "test",
        "https://example.com/test.apk", testApk.length(), digest(testApk), 26);
    try {
      updater.verify(testApk, info);
      fail("Another application's APK must be refused even when its digest is correct");
    } catch (Exception expected) {
      assertTrue(expected.getMessage().contains("应用或版本不符"));
    }
  }

  public void testMetadataNumbersAndCancellationAreStrict() throws Exception {
    assertEquals(5L, AppUpdater.integer(new JSONObject().put("code", 5), "code"));
    for (Object value : new Object[] {"5", 5.25, JSONObject.NULL}) {
      try {
        AppUpdater.integer(new JSONObject().put("code", value), "code");
        fail("Release integer fields must not accept coercion");
      } catch (IOException expected) { }
    }
    AppUpdater.Cancellation cancel = new AppUpdater.Cancellation();
    cancel.cancel();
    try {
      AppUpdater.open("https://example.com/update.json", cancel);
      fail("Cancelled work must stop before connecting to the network");
    } catch (InterruptedIOException expected) { }
  }

  public void testUpdateSettingsRequireParentAndAutomaticCheckCanBeDisabled() throws Exception {
    Intent intent = new Intent(Intent.ACTION_MAIN).setClassName("top.mossmoss.lessonisland", "top.mossmoss.lessonisland.MainActivity")
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(intent);
    SharedPreferences preferences = activity.getSharedPreferences("app_updates", 0);
    boolean existed = preferences.contains("automatic"), before = preferences.getBoolean("automatic", true);
    final Throwable[] failure = {null};
    try {
      getInstrumentation().runOnMainSync(() -> {
        AppUpdateFlow flow = new AppUpdateFlow(activity);
        try {
          android.widget.LinearLayout container = new android.widget.LinearLayout(activity);
          activity.parent = false;
          flow.addSettings(container);
          assertEquals(0, container.getChildCount());
          flow.check(true);
          assertFalse(flow.running);
          activity.parent = true;
          flow.addSettings(container);
          android.widget.Switch automatic = container.findViewWithTag("updates:automatic");
          assertNotNull(automatic);
          automatic.setChecked(true);
          automatic.setChecked(false);
          assertFalse(preferences.getBoolean("automatic", true));
          flow.onPause();
          assertFalse(flow.available());
        } catch (Throwable e) { failure[0] = e; }
        finally { flow.close(); activity.finish(); }
      });
      if (failure[0] != null) throw new Exception(failure[0]);
    } finally {
      SharedPreferences.Editor editor = preferences.edit();
      if (existed) editor.putBoolean("automatic", before); else editor.remove("automatic");
      editor.commit();
    }
  }
}
