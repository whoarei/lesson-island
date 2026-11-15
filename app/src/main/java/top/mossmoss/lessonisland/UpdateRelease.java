package top.mossmoss.lessonisland;

import java.io.File;
import java.io.FileInputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.util.Locale;

/** Validated public release metadata; independent of Android UI and application data. */
final class UpdateRelease {
  static final long MAX_APK_BYTES = 128L * 1024 * 1024;
  final String versionName, notes, apkUrl, sha256;
  final long versionCode, apkSize;
  final int minSdk;

  UpdateRelease(String name, long code, String notes, String url, long size, String digest, int minSdk) {
    if (name == null || !name.matches("(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)"))
      throw new IllegalArgumentException("更新信息中的版本号无效");
    if (code < 1 || code > 2100000000L || size < 1 || size > MAX_APK_BYTES || minSdk < 26 || minSdk > 1000)
      throw new IllegalArgumentException("更新信息中的版本或安装包大小无效");
    if (notes == null || notes.length() > 24000 || digest == null || !digest.matches("[0-9a-fA-F]{64}"))
      throw new IllegalArgumentException("更新说明或校验信息无效");
    requireHttps(url);
    versionName = name;
    versionCode = code;
    this.notes = notes;
    apkUrl = url;
    apkSize = size;
    sha256 = digest.toLowerCase(Locale.ROOT);
    this.minSdk = minSdk;
  }

  static URI requireHttps(String value) {
    try {
      URI uri = new URI(value);
      if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
          || uri.getHost().isEmpty() || uri.getUserInfo() != null || uri.getFragment() != null)
        throw new IllegalArgumentException("更新地址必须是有效的 HTTPS 地址");
      return uri;
    } catch (java.net.URISyntaxException | NullPointerException e) {
      throw new IllegalArgumentException("更新地址无效");
    }
  }

  void verifyFile(File file) throws Exception {
    if (!file.isFile() || file.length() != apkSize) throw new Exception("安装包不完整，请重新下载");
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    byte[] buffer = new byte[32768];
    try (FileInputStream input = new FileInputStream(file)) {
      int read;
      while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
    }
    StringBuilder actual = new StringBuilder(64);
    for (byte b : digest.digest()) actual.append(String.format(Locale.ROOT, "%02x", b & 255));
    if (!MessageDigest.isEqual(actual.toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        sha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
      throw new Exception("安装包校验失败，请重新下载");
  }

  void verifyCandidate(String expectedPackage, long currentCode, byte[] installedSigner,
      String candidatePackage, long candidateCode, String candidateName, int candidateMinSdk,
      byte[] candidateSigner) throws Exception {
    if (versionCode <= currentCode) throw new Exception("此安装包不是更新版本");
    if (!expectedPackage.equals(candidatePackage) || candidateCode != versionCode || !versionName.equals(candidateName))
      throw new Exception("安装包的应用或版本不符，请重新下载");
    if (candidateMinSdk != minSdk) throw new Exception("安装包的系统版本要求与更新信息不符");
    if (installedSigner == null || candidateSigner == null || installedSigner.length != 32 || candidateSigner.length != 32
        || !MessageDigest.isEqual(installedSigner, candidateSigner))
      throw new Exception("安装包签名与现有应用不同，已停止更新");
  }
}
