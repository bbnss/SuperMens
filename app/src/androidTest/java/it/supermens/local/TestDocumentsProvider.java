// Copyright (C) 2026 BBNSS
// GPL-3.0-only with the Google SDK linking exception in LICENSE_EXCEPTION.md.

package it.supermens.local;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Runs in the separate test APK process, so it uses only Android/Java runtime classes. */
public class TestDocumentsProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private synchronized File fixture(Uri uri) throws IOException {
        File file = new File(getContext().getFilesDir(), "test-" + uri.getLastPathSegment() + ("video".equals(uri.getLastPathSegment()) ? ".mp4" : ".pdf"));
        if (!file.exists()) {
            try (FileOutputStream output = new FileOutputStream(file)) {
                if ("video".equals(uri.getLastPathSegment())) {
                    try (java.io.InputStream input = getContext().getAssets().open("video-silent.mp4")) {input.transferTo(output);}
                } else if ("broken".equals(uri.getLastPathSegment())) output.write("invalid pdf".getBytes());
                else {
                    PdfDocument document = new PdfDocument();
                    try {
                        for (int index = 0; index < 2; index++) {
                            PdfDocument.Page page = document.startPage(new PdfDocument.PageInfo.Builder(80, 100, index + 1).create());
                            document.finishPage(page);
                        }
                        document.writeTo(output);
                    } finally { document.close(); }
                }
            }
        }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
        try {
            File file = fixture(uri);
            if ("pipe".equals(uri.getLastPathSegment())) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> {
                    try (FileInputStream input = new FileInputStream(file);
                         ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        byte[] buffer = new byte[8192]; int count;
                        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                    } catch (IOException error) { /* The seekability probe may close its pipe early. */ }
                }).start();
                return pipe[0];
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (IOException error) { throw new java.io.FileNotFoundException(error.toString()); }
    }
    @Override public String getType(Uri uri) { return "video".equals(uri.getLastPathSegment()) ? "video/mp4" : "application/pdf"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] columns = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor result = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) values[index] = OpenableColumns.DISPLAY_NAME.equals(columns[index]) ? ("video".equals(uri.getLastPathSegment()) ? "video-silent.mp4" : "document.pdf") : 0L;
        result.addRow(values); return result;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
