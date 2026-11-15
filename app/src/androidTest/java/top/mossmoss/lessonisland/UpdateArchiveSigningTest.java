package top.mossmoss.lessonisland;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.test.InstrumentationTestCase;
import java.security.MessageDigest;

/** Parses the actual, already installed signed APKs; no network, installation or keys are needed. */
public final class UpdateArchiveSigningTest extends InstrumentationTestCase {
  public void testInstalledApplicationArchiveRetainsItsVerifiedSigner() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    AppUpdater updater = new AppUpdater(context);
    PackageInfo installed = updater.installed();
    PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(
        context.getApplicationInfo().sourceDir, AppUpdater.archiveSigningFlags());
    assertNotNull("The installed application APK must be parseable", archive);
    assertEquals(installed.packageName, archive.packageName);
    assertEquals(AppUpdater.code(installed), AppUpdater.code(archive));
    assertTrue("Archive parsing must return the same certificate as the installed package",
        MessageDigest.isEqual(AppUpdater.signer(installed), AppUpdater.signer(archive)));
  }

  public void testInstrumentationArchiveAlsoExposesTheExistingUserSigner() throws Exception {
    Context target = getInstrumentation().getTargetContext();
    Context tests = getInstrumentation().getContext();
    PackageInfo archive = target.getPackageManager().getPackageArchiveInfo(
        tests.getApplicationInfo().sourceDir, AppUpdater.archiveSigningFlags());
    assertNotNull("The signed instrumentation APK must be parseable", archive);
    assertEquals(target.getPackageName() + ".test", archive.packageName);
    assertTrue("Both dedicated test APKs must use the existing user certificate",
        MessageDigest.isEqual(AppUpdater.signer(new AppUpdater(target).installed()), AppUpdater.signer(archive)));
  }
}
