package top.mossmoss.lessonisland;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Read-only access to one verified update APK; no other private application files are shared. */
public final class UpdateApkProvider extends ContentProvider {
  static Uri uri(Context context) {
    return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
        .path("/apk/update.apk").build();
  }

  File file(Uri requested) throws FileNotFoundException {
    if (getContext() == null || !uri(getContext()).equals(requested)) throw new FileNotFoundException("Unknown update URI");
    File file = new File(getContext().getCacheDir(), "updates/update.apk");
    if (!file.isFile()) throw new FileNotFoundException("Update APK not found");
    return file;
  }

  @Override public boolean onCreate() { return true; }

  @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

  @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    try {
      File file = file(uri);
      String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
      MatrixCursor cursor = new MatrixCursor(columns);
      Object[] values = new Object[columns.length];
      for (int i = 0; i < columns.length; i++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "LessonIsland-update.apk";
        if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
      }
      cursor.addRow(values);
      return cursor;
    } catch (FileNotFoundException e) { throw new IllegalArgumentException(e.getMessage()); }
  }

  @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Update APK is read-only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
  @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
  @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
