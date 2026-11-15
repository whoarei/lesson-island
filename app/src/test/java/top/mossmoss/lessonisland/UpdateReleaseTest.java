package top.mossmoss.lessonisland;

import static org.junit.Assert.*;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UpdateReleaseTest {
  static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  @Rule public TemporaryFolder folder = new TemporaryFolder();

  interface Checked { void run() throws Exception; }

  void rejects(Checked action) throws Exception {
    try { action.run(); fail("Expected invalid update to be rejected"); }
    catch (Exception expected) { assertNotNull(expected.getMessage()); }
  }

  UpdateRelease release() {
    return new UpdateRelease("0.3.0", 5, "课程学习改进", "https://github.com/owner/releases/download/v0.3.0/app.apk", 3, ABC_SHA256, 26);
  }

  @Test public void metadataRequiresVersionSizeDigestAndHttps() throws Exception {
    assertEquals(5, release().versionCode);
    String[] invalidUrls = {"http://example.com/app.apk", "file:///tmp/app.apk", "https://name:password@example.com/app.apk", "https://example.com/app.apk#fragment", "/app.apk"};
    for (String url : invalidUrls) rejects(() -> new UpdateRelease("0.3.0", 5, "notes", url, 3, ABC_SHA256, 26));
    rejects(() -> new UpdateRelease("00.3.0", 5, "notes", "https://example.com/app.apk", 3, ABC_SHA256, 26));
    rejects(() -> new UpdateRelease("0.3.0-beta", 5, "notes", "https://example.com/app.apk", 3, ABC_SHA256, 26));
    rejects(() -> new UpdateRelease("0.3.0", 0, "notes", "https://example.com/app.apk", 3, ABC_SHA256, 26));
    rejects(() -> new UpdateRelease("0.3.0", 5, "notes", "https://example.com/app.apk", 0, ABC_SHA256, 26));
    rejects(() -> new UpdateRelease("0.3.0", 5, "notes", "https://example.com/app.apk", UpdateRelease.MAX_APK_BYTES + 1, ABC_SHA256, 26));
    rejects(() -> new UpdateRelease("0.3.0", 5, "notes", "https://example.com/app.apk", 3, "bad digest", 26));
  }

  @Test public void downloadedContentMustMatchBothSizeAndDigest() throws Exception {
    File apk = folder.newFile("update.apk");
    Files.write(apk.toPath(), "abc".getBytes(StandardCharsets.UTF_8));
    release().verifyFile(apk);
    Files.write(apk.toPath(), "abd".getBytes(StandardCharsets.UTF_8));
    rejects(() -> release().verifyFile(apk));
    Files.write(apk.toPath(), "ab".getBytes(StandardCharsets.UTF_8));
    rejects(() -> release().verifyFile(apk));
  }

  @Test public void onlyExactNewerPackageWithOriginalSignerCanBeInstalled() throws Exception {
    byte[] signer = new byte[32], otherSigner = new byte[32];
    signer[0] = 1;
    otherSigner[0] = 2;
    String name = "top.mossmoss.lessonisland";
    release().verifyCandidate(name, 4, signer, name, 5, "0.3.0", 26, signer);
    rejects(() -> release().verifyCandidate(name, 5, signer, name, 5, "0.3.0", 26, signer));
    rejects(() -> release().verifyCandidate(name, 6, signer, name, 5, "0.3.0", 26, signer));
    rejects(() -> release().verifyCandidate(name, 4, signer, "another.app", 5, "0.3.0", 26, signer));
    rejects(() -> release().verifyCandidate(name, 4, signer, name, 6, "0.3.0", 26, signer));
    rejects(() -> release().verifyCandidate(name, 4, signer, name, 5, "0.3.1", 26, signer));
    rejects(() -> release().verifyCandidate(name, 4, signer, name, 5, "0.3.0", 28, signer));
    rejects(() -> release().verifyCandidate(name, 4, signer, name, 5, "0.3.0", 26, otherSigner));
    rejects(() -> release().verifyCandidate(name, 4, signer, name, 5, "0.3.0", 26, null));
  }
}
