package top.mossmoss.lessonisland;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONObject;

/** Public HTTPS update checks and private-cache downloads. Never reads API credentials. */
final class AppUpdater {
  interface Progress { void update(long bytes, long total); }

  static final class Cancellation {
    volatile boolean cancelled;
    volatile HttpsURLConnection connection;

    void cancel() {
      cancelled = true;
      HttpsURLConnection current = connection;
      if (current != null) current.disconnect();
    }

    void check() throws InterruptedIOException {
      if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
    }
  }

  final Context context;

  AppUpdater(Context context) { this.context = context.getApplicationContext(); }

  String manifestUrl() { return context.getString(R.string.app_update_manifest_url).trim(); }

  static long code(PackageInfo info) {
    return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
  }

  PackageInfo installed() throws PackageManager.NameNotFoundException {
    return context.getPackageManager().getPackageInfo(context.getPackageName(), signingFlags());
  }

  static int signingFlags() {
    return Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
  }

  static int archiveSigningFlags() {
    // Older PackageManager archive parsers only collect certificates when GET_SIGNATURES is set,
    // even if GET_SIGNING_CERTIFICATES was requested for the returned SigningInfo.
    return signingFlags() | PackageManager.GET_SIGNATURES;
  }

  static byte[] signer(PackageInfo info) throws Exception {
    Signature[] signatures = Build.VERSION.SDK_INT >= 28
        ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()
        : info.signatures;
    if (signatures == null || signatures.length != 1) throw new Exception("无法确认安装包的签名证书");
    return MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());
  }

  static HttpsURLConnection open(String address, Cancellation cancellation) throws Exception {
    URL url = UpdateRelease.requireHttps(address).toURL();
    for (int redirects = 0; redirects <= 5; redirects++) {
      cancellation.check();
      HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
      cancellation.connection = connection;
      connection.setConnectTimeout(15000);
      connection.setReadTimeout(20000);
      connection.setInstanceFollowRedirects(false);
      connection.setRequestProperty("User-Agent", "LessonIsland-Android-Update");
      connection.setRequestProperty("Accept", "application/octet-stream, application/json");
      int status;
      try {
        status = connection.getResponseCode();
      } catch (Exception e) {
        connection.disconnect();
        cancellation.connection = null;
        throw e;
      }
      if (status >= 300 && status <= 399) {
        String target = connection.getHeaderField("Location");
        connection.disconnect();
        if (target == null || redirects == 5) throw new IOException("更新服务重定向异常");
        url = UpdateRelease.requireHttps(new URL(url, target).toString()).toURL();
        continue;
      }
      if (status != 200) {
        connection.disconnect();
        throw new IOException(status == 404 ? "暂时没有可下载的更新，请稍后重试" : "更新服务暂时不可用，请稍后重试");
      }
      return connection;
    }
    throw new IOException("更新服务重定向异常");
  }

  UpdateRelease check(Cancellation cancellation) throws Exception {
    if (manifestUrl().isEmpty()) throw new IOException("更新服务尚未配置");
    HttpsURLConnection connection = open(manifestUrl(), cancellation);
    try (InputStream input = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        cancellation.check();
        if (out.size() + read > 128 * 1024) throw new IOException("更新信息过大");
        out.write(buffer, 0, read);
      }
      JSONObject data;
      try { data = new JSONObject(out.toString(StandardCharsets.UTF_8.name())); }
      catch (org.json.JSONException e) { throw new IOException("更新信息暂时无法读取，请稍后重试"); }
      if (data.optInt("schemaVersion", -1) != 1) throw new IOException("暂不支持此更新信息格式");
      try {
        return new UpdateRelease(data.getString("versionName"), integer(data, "versionCode"),
            data.getString("notes"), data.getString("apkUrl"), integer(data, "apkSize"),
            data.getString("sha256"), Math.toIntExact(integer(data, "minSdk")));
      } catch (org.json.JSONException | ArithmeticException e) {
        throw new IOException("更新信息不完整，请稍后重试");
      }
    } finally {
      connection.disconnect();
      cancellation.connection = null;
    }
  }

  static long integer(JSONObject object, String name) throws Exception {
    Object value = object.get(name);
    if (!(value instanceof Integer) && !(value instanceof Long)) throw new IOException("更新信息中的版本或大小格式无效");
    return ((Number) value).longValue();
  }

  File download(UpdateRelease release, Cancellation cancellation, Progress progress) throws Exception {
    File directory = new File(context.getCacheDir(), "updates");
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法准备更新下载目录");
    File partial = new File(directory, "pending-update.apk"), target = new File(directory, "update.apk");
    HttpsURLConnection connection = null;
    boolean complete = false;
    try {
      connection = open(release.apkUrl, cancellation);
      long length = connection.getContentLengthLong();
      if (length > 0 && length != release.apkSize) throw new IOException("安装包大小与更新信息不符");
      try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
        byte[] buffer = new byte[32768];
        long bytes = 0, lastProgress = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
          cancellation.check();
          bytes += read;
          if (bytes > release.apkSize) throw new IOException("安装包大小与更新信息不符");
          output.write(buffer, 0, read);
          long now = System.nanoTime();
          if (now - lastProgress > 200000000L) {
            progress.update(bytes, release.apkSize);
            lastProgress = now;
          }
        }
        output.getFD().sync();
      }
      cancellation.check();
      verify(partial, release);
      cancellation.check();
      Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
      progress.update(release.apkSize, release.apkSize);
      complete = true;
      return target;
    } finally {
      if (connection != null) connection.disconnect();
      cancellation.connection = null;
      if (!complete) Files.deleteIfExists(partial.toPath());
    }
  }

  void verify(File file, UpdateRelease release) throws Exception {
    release.verifyFile(file);
    PackageInfo current = installed();
    if (release.versionCode <= code(current)) throw new Exception("此安装包不是更新版本");
    if (Build.VERSION.SDK_INT < release.minSdk) throw new Exception("当前设备的 Android 版本不满足新版要求");
    PackageInfo candidate = context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), archiveSigningFlags());
    if (candidate == null || candidate.applicationInfo == null)
      throw new Exception("安装包的应用或版本不符，请重新下载");
    release.verifyCandidate(context.getPackageName(), code(current), signer(current), candidate.packageName,
        code(candidate), candidate.versionName, candidate.applicationInfo.minSdkVersion, signer(candidate));
  }
}
