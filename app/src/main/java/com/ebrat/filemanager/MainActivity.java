package com.ebrat.filemanager;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private File current = Environment.getExternalStorageDirectory();
    private File clipboard;           // file waiting to be pasted
    private boolean clipboardIsMove;  // true = Move, false = Copy
    private boolean askedPermission;
    private final List<File> items = new ArrayList<>();
    private ArrayAdapter<File> adapter;
    private TextView pathView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);
        pathView = findViewById(R.id.path);
        ListView list = findViewById(R.id.list);

        // ---- How each row looks (edit this to change the row design) ----
        adapter = new ArrayAdapter<File>(this, android.R.layout.simple_list_item_2, android.R.id.text1, items) {
            @Override
            public View getView(int pos, View v, ViewGroup parent) {
                v = super.getView(pos, v, parent);
                File f = getItem(pos);
                ((TextView) v.findViewById(android.R.id.text1))
                        .setText((f.isDirectory() ? "\uD83D\uDCC1 " : "\uD83D\uDCC4 ") + f.getName());
                String info;
                if (f.isDirectory()) {
                    String[] c = f.list();
                    info = (c == null ? 0 : c.length) + " items";
                } else {
                    info = readableSize(f.length());
                }
                info += "  \u2022  " + DateFormat.getDateInstance().format(new Date(f.lastModified()));
                ((TextView) v.findViewById(android.R.id.text2)).setText(info);
                return v;
            }
        };
        list.setAdapter(adapter);

        list.setOnItemClickListener((p, v, pos, id) -> {
            File f = items.get(pos);
            if (f.isDirectory()) { current = f; load(); } else openFile(f);
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> { showMenu(items.get(pos)); return true; });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (hasAccess()) load();
        else if (!askedPermission) { askedPermission = true; requestAccess(); }
    }

    // ---------------- Permission ----------------
    private boolean hasAccess() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        if (Build.VERSION.SDK_INT >= 23)
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        return true;
    }

    private void requestAccess() {
        Toast.makeText(this, "Please allow file access", Toast.LENGTH_LONG).show();
        if (Build.VERSION.SDK_INT >= 30) {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (hasAccess()) load();
    }

    // ---------------- Listing ----------------
    private void load() {
        items.clear();
        File[] files = current.listFiles();
        if (files != null) {
            Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            items.addAll(Arrays.asList(files));
        }
        adapter.notifyDataSetChanged();
        pathView.setText(current.getAbsolutePath()
                + (clipboard != null ? "   [" + (clipboardIsMove ? "Move" : "Copy") + ": " + clipboard.getName() + "]" : ""));
    }

    @Override
    public void onBackPressed() {
        File root = Environment.getExternalStorageDirectory();
        if (!current.equals(root) && current.getParentFile() != null) {
            current = current.getParentFile();
            load();
        } else super.onBackPressed();
    }

    // ---------------- Top menu (add new menu items here) ----------------
    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "New folder");
        m.add(0, 2, 1, "Paste");
        m.add(0, 3, 2, "Refresh");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1: askName("New folder", "", n -> {
                        if (new File(current, n).mkdirs()) load();
                        else toast("Could not create folder");
                    }); break;
            case 2: paste(); break;
            case 3: load(); break;
        }
        return true;
    }

    // ---------------- Long-press menu (add new actions here) ----------------
    private void showMenu(File f) {
        String[] opts = {"Open", "Rename", "Copy", "Move", "Delete", "Details"};
        new AlertDialog.Builder(this).setTitle(f.getName()).setItems(opts, (d, i) -> {
            switch (i) {
                case 0: if (f.isDirectory()) { current = f; load(); } else openFile(f); break;
                case 1: askName("Rename", f.getName(), n -> {
                            if (f.renameTo(new File(f.getParentFile(), n))) load();
                            else toast("Rename failed");
                        }); break;
                case 2: clipboard = f; clipboardIsMove = false; load(); toast("Copied. Open a folder > menu > Paste"); break;
                case 3: clipboard = f; clipboardIsMove = true; load(); toast("Ready to move. Open a folder > menu > Paste"); break;
                case 4: confirmDelete(f); break;
                case 5: showDetails(f); break;
            }
        }).show();
    }

    private void confirmDelete(File f) {
        new AlertDialog.Builder(this).setTitle("Delete?").setMessage(f.getName())
                .setPositiveButton("Delete", (d, w) -> { deleteRec(f); load(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void showDetails(File f) {
        String t = "Path: " + f.getAbsolutePath()
                + "\nSize: " + readableSize(f.isDirectory() ? dirSize(f) : f.length())
                + "\nModified: " + DateFormat.getDateTimeInstance().format(new Date(f.lastModified()))
                + "\nReadable: " + f.canRead() + "\nWritable: " + f.canWrite();
        new AlertDialog.Builder(this).setTitle("Details").setMessage(t).setPositiveButton("OK", null).show();
    }

    // ---------------- Open / Paste ----------------
    private void openFile(File f) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", f);
            String ext = MimeTypeMap.getFileExtensionFromUrl(f.getName());
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, mime == null ? "*/*" : mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Open with"));
        } catch (Exception e) { toast("Cannot open: " + e.getMessage()); }
    }

    private void paste() {
        if (clipboard == null || !clipboard.exists()) { toast("Nothing to paste"); return; }
        final File src = clipboard;
        File dest = new File(current, src.getName());
        for (int n = 1; dest.exists(); n++) dest = new File(current, "copy" + n + "_" + src.getName());
        final File target = dest;
        final boolean move = clipboardIsMove;
        toast("Working...");
        new Thread(() -> {
            String msg = "Done";
            try {
                if (move && src.renameTo(target)) { /* fast move */ }
                else { copyRec(src, target); if (move) deleteRec(src); }
            } catch (IOException e) { msg = "Failed: " + e.getMessage(); }
            final String m = msg;
            runOnUiThread(() -> { if (!move || m.startsWith("Failed")) {} else clipboard = null; toast(m); load(); });
        }).start();
    }

    // ---------------- Helpers ----------------
    private interface Callback { void run(String text); }

    private void askName(String title, String initial, Callback cb) {
        EditText et = new EditText(this);
        et.setText(initial);
        new AlertDialog.Builder(this).setTitle(title).setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    String n = et.getText().toString().trim();
                    if (!n.isEmpty()) cb.run(n);
                }).setNegativeButton("Cancel", null).show();
    }

    private void copyRec(File s, File d) throws IOException {
        if (s.isDirectory()) {
            d.mkdirs();
            File[] c = s.listFiles();
            if (c != null) for (File x : c) copyRec(x, new File(d, x.getName()));
        } else {
            try (InputStream in = new FileInputStream(s); OutputStream out = new FileOutputStream(d)) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
        }
    }

    private boolean deleteRec(File f) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File x : c) deleteRec(x);
        }
        return f.delete();
    }

    private long dirSize(File f) {
        long t = 0;
        File[] c = f.listFiles();
        if (c != null) for (File x : c) t += x.isDirectory() ? dirSize(x) : x.length();
        return t;
    }

    private static String readableSize(long b) {
        if (b < 1024) return b + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = b; int i = -1;
        do { v /= 1024; i++; } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.US, "%.1f %s", v, u[i]);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
