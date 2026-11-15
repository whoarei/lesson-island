package top.mossmoss.lessonisland;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Parent-only check, download and explicit system-installer handoff. */
final class AppUpdateFlow implements AutoCloseable {
  static final int INSTALL_PERMISSION = 301, INSTALL_APK = 302;
  final MainActivity app;
  final AppUpdater updater;
  final SharedPreferences preferences;
  final ExecutorService jobs = Executors.newSingleThreadExecutor();
  boolean foreground = true, closed, running;
  int generation;
  AppUpdater.Cancellation cancellation;
  AlertDialog dialog;
  TextView statusView;
  Button installButton;
  UpdateRelease pendingRelease;
  File pendingApk;

  AppUpdateFlow(MainActivity app) {
    this.app = app;
    updater = new AppUpdater(app);
    preferences = app.getSharedPreferences("app_updates", 0);
  }

  boolean available() { return !closed && foreground && app.parent && !app.isFinishing(); }

  void addSettings(LinearLayout target) throws Exception {
    if (!app.parent) return;
    LinearLayout card = app.card(target);
    app.add(card, app.text("应用更新", 21, MainActivity.INK, true));
    app.space(card, 8);
    app.add(card, app.text("当前版本 " + updater.installed().versionName, 14, MainActivity.MUTED, false));
    statusView = app.text("检查新版后，可查看更新说明并选择下载。", 13, MainActivity.MUTED, false);
    statusView.setTag("updates:status");
    app.add(card, statusView);
    app.action(card, "检查更新", false, () -> check(true));
    installButton = app.button("安装已下载更新", false, this::confirmInstall);
    installButton.setTag("updates:install-downloaded");
    installButton.setVisibility(pendingRelease != null && pendingApk != null && pendingApk.isFile() ? View.VISIBLE : View.GONE);
    app.add(card, installButton);
    Switch automatic = new Switch(app);
    automatic.setText("进入家长模式时，每天自动检查一次");
    automatic.setTextColor(MainActivity.INK);
    automatic.setTextSize(14);
    automatic.setMinHeight(app.dp(48));
    automatic.setTag("updates:automatic");
    automatic.setChecked(preferences.getBoolean("automatic", true));
    automatic.setOnCheckedChangeListener((button, checked) -> preferences.edit().putBoolean("automatic", checked).apply());
    app.add(card, automatic);
  }

  void checkAutomatically() {
    if (!available() || !preferences.getBoolean("automatic", true) || updater.manifestUrl().isEmpty()) return;
    String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    if (!day.equals(preferences.getString("lastCheckDay", ""))) check(false);
  }

  void status(String value) {
    if (statusView != null) statusView.setText(value);
  }

  void dismiss() {
    if (dialog != null) dialog.dismiss();
    dialog = null;
  }

  void check(boolean manual) {
    if (!available()) return;
    if (running) {
      if (manual) app.toast("正在处理更新，请稍候");
      return;
    }
    if (updater.manifestUrl().isEmpty()) {
      if (manual) app.toast("更新服务尚未配置");
      return;
    }
    String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
    preferences.edit().putString("lastCheckDay", day).apply();
    int token = ++generation;
    running = true;
    cancellation = new AppUpdater.Cancellation();
    AppUpdater.Cancellation cancel = cancellation;
    status("正在检查更新…");
    if (manual) {
      dismiss();
      dialog = new AlertDialog.Builder(app).setTitle("检查更新").setMessage("正在检查新版本…")
          .setNegativeButton("取消", (d, w) -> cancelCurrent()).create();
      dialog.setOnCancelListener(d -> cancelCurrent());
      dialog.show();
    }
    jobs.execute(() -> {
      try {
        UpdateRelease release = updater.check(cancel);
        long installed = AppUpdater.code(updater.installed());
        app.runOnUiThread(() -> {
          if (token != generation || !available()) return;
          running = false;
          dismiss();
          if (release.versionCode <= installed) {
            status("已是最新版本");
            if (manual) app.toast("已是最新版本");
          } else {
            status("发现新版本 " + release.versionName);
            showRelease(release);
          }
        });
      } catch (Exception e) {
        app.runOnUiThread(() -> {
          if (token != generation || !available()) return;
          running = false;
          dismiss();
          status(manual ? "检查失败，可稍后重试。" : "检查新版后，可查看更新说明并选择下载。");
          if (manual) app.toast("检查更新未完成：" + message(e));
        });
      }
    });
  }

  void showRelease(UpdateRelease release) {
    if (!available()) return;
    dismiss();
    String details = release.notes.isEmpty() ? "优化与问题修复。" : release.notes;
    details += String.format(Locale.CHINA, "\n\n安装包 %.1f MB", release.apkSize / 1048576.0);
    AlertDialog.Builder builder = new AlertDialog.Builder(app).setTitle("发现新版 " + release.versionName)
        .setMessage(details).setNegativeButton("稍后", null);
    if (Build.VERSION.SDK_INT < release.minSdk)
      builder.setMessage(details + "\n\n当前设备的 Android 版本不满足新版要求。");
    else builder.setPositiveButton("下载更新", (d, w) -> download(release));
    dialog = builder.show();
  }

  void download(UpdateRelease release) {
    if (!available() || running) return;
    int token = ++generation;
    running = true;
    cancellation = new AppUpdater.Cancellation();
    AppUpdater.Cancellation cancel = cancellation;
    dismiss();
    LinearLayout content = app.col();
    content.setPadding(app.dp(24), app.dp(16), app.dp(24), app.dp(8));
    TextView progressText = app.text("正在下载…", 14, MainActivity.INK, false);
    app.add(content, progressText);
    ProgressBar progress = new ProgressBar(app, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    content.addView(progress, new LinearLayout.LayoutParams(-1, app.dp(24)));
    dialog = new AlertDialog.Builder(app).setTitle("下载 " + release.versionName).setView(content)
        .setNegativeButton("取消下载", (d, w) -> cancelCurrent()).create();
    dialog.setOnCancelListener(d -> cancelCurrent());
    dialog.show();
    jobs.execute(() -> {
      try {
        File file = updater.download(release, cancel, (bytes, total) -> app.runOnUiThread(() -> {
          if (token != generation || !available()) return;
          int percent = (int) (bytes * 100 / total);
          progress.setProgress(percent);
          progressText.setText(percent + "% · " + String.format(Locale.CHINA, "%.1f / %.1f MB", bytes / 1048576.0, total / 1048576.0));
        }));
        app.runOnUiThread(() -> {
          if (token != generation || !available()) return;
          running = false;
          dismiss();
          pendingRelease = release;
          pendingApk = file;
          if (installButton != null) installButton.setVisibility(View.VISIBLE);
          status("下载及校验完成");
          confirmInstall();
        });
      } catch (Exception e) {
        app.runOnUiThread(() -> {
          if (token != generation || !available()) return;
          running = false;
          dismiss();
          status("下载未完成，可重试。");
          dialog = new AlertDialog.Builder(app).setTitle("更新下载未完成").setMessage(message(e))
              .setPositiveButton("重新下载", (d, w) -> download(release)).setNegativeButton("稍后", null).show();
        });
      }
    });
  }

  void confirmInstall() {
    if (!available() || pendingRelease == null || pendingApk == null) return;
    dismiss();
    dialog = new AlertDialog.Builder(app).setTitle("安装 " + pendingRelease.versionName + "？")
        .setMessage("安装包校验通过。接下来由系统确认安装，升级会保留本机课程与学习记录。")
        .setPositiveButton("继续安装", (d, w) -> verifyAndInstall()).setNegativeButton("稍后", null).show();
  }

  void verifyAndInstall() {
    if (!available() || running || pendingApk == null || pendingRelease == null) return;
    int token = ++generation;
    running = true;
    File file = pendingApk;
    UpdateRelease release = pendingRelease;
    jobs.execute(() -> {
      try {
        updater.verify(file, release);
        app.runOnUiThread(() -> {
          if (token != generation || !available()) return;
          running = false;
          try {
            if (!app.getPackageManager().canRequestPackageInstalls()) {
              dialog = new AlertDialog.Builder(app).setTitle("允许安装应用更新")
                  .setMessage("请在系统设置中允许课文岛安装应用，返回后继续确认更新。")
                  .setPositiveButton("前往设置", (d, w) -> {
                    app.externalPending = true;
                    try {
                      app.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                          Uri.parse("package:" + app.getPackageName())), INSTALL_PERMISSION);
                    } catch (Exception e) {
                      app.externalPending = false;
                      app.toast("无法打开安装授权设置，请在系统设置中手动允许后重试");
                    }
                  }).setNegativeButton("稍后", null).show();
              return;
            }
            Uri uri = UpdateApkProvider.uri(app);
            Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true);
            intent.setClipData(ClipData.newRawUri("Lesson Island update", uri));
            app.externalPending = true;
            app.startActivityForResult(intent, INSTALL_APK);
          } catch (Exception e) {
            app.externalPending = false;
            app.toast("无法打开系统安装器：" + message(e));
          }
        });
      } catch (Exception e) {
        app.runOnUiThread(() -> {
          if (token != generation || !available()) return;
          running = false;
          app.toast("安装校验未通过：" + message(e));
        });
      }
    });
  }

  boolean onActivityResult(int request, int result, Intent data) {
    if (request != INSTALL_PERMISSION && request != INSTALL_APK) return false;
    app.externalPending = false;
    foreground = true;
    if (!available()) return true;
    if (request == INSTALL_PERMISSION) {
      if (app.getPackageManager().canRequestPackageInstalls()) confirmInstall();
      else app.toast("尚未允许安装更新，可在家长设置中重试");
    } else if (result != android.app.Activity.RESULT_OK) {
      status("安装未完成，可稍后重试。");
    }
    return true;
  }

  void cancelCurrent() {
    ++generation;
    if (cancellation != null) cancellation.cancel();
    running = false;
    dismiss();
    status("更新已取消，可重新检查。");
  }

  void onPageChanged() {
    if (!app.parent) {
      cancelCurrent();
      pendingRelease = null;
      pendingApk = null;
      statusView = null;
      installButton = null;
    }
  }

  void onResume() { foreground = true; }

  void onPause() {
    foreground = false;
    if (running) cancelCurrent();
    dismiss();
  }

  @Override public void close() {
    closed = true;
    cancelCurrent();
    jobs.shutdownNow();
  }

  static String message(Exception error) {
    if (error instanceof java.net.SocketTimeoutException) return "连接超时，请检查网络后重试";
    if (error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException
        || error instanceof java.net.NoRouteToHostException) return "无法连接更新服务，请检查网络后重试";
    if (error instanceof javax.net.ssl.SSLException) return "无法建立安全连接，请检查网络和系统时间后重试";
    if (error instanceof android.content.ActivityNotFoundException) return "当前设备无法打开系统安装器";
    String value = error.getMessage();
    return value == null || value.isEmpty() ? "请检查网络后重试" : value;
  }
}
