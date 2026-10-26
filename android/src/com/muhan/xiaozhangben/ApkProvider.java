package com.muhan.xiaozhangben;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简 FileProvider：把下载好的 APK 以 content:// 方式交给系统安装器。
 * 项目不依赖 androidx，所以用 framework 的 ContentProvider 自己实现一个最小版本。
 *
 * 用法：content://com.muhan.xiaozhangben.apkprovider/<URL 编码后的绝对路径>
 * 安全：只放行 App 私有下载目录下的文件，避免被拿来读取任意路径。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.muhan.xiaozhangben.apkprovider";

    @Override
    public boolean onCreate() {
        return true;
    }

    /** 构造可供安装器访问的 content:// URI */
    public static Uri uriFor(File file) {
        return Uri.parse("content://" + AUTHORITY + "/" + Uri.encode(file.getAbsolutePath()));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String encoded = uri.getLastPathSegment();
        if (TextUtils.isEmpty(encoded)) throw new FileNotFoundException("empty path");
        String path = Uri.decode(encoded);
        File f = new File(path);
        // 安全校验：只允许本 App 私有下载目录内的文件
        File base = getContext().getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS);
        String fp = canonical(f);
        String bp = canonical(base);
        if (fp == null || bp == null || !fp.startsWith(bp)) {
            throw new FileNotFoundException("not allowed: " + path);
        }
        if (!f.exists()) throw new FileNotFoundException(path);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 解析为规范路径；失败时返回 null（保守拒绝） */
    private static String canonical(File f) {
        if (f == null) return null;
        try { return f.getCanonicalPath(); } catch (Exception e) { return null; }
    }

    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri uri, ContentValues v) { return null; }
    @Override public int delete(Uri uri, String s, String[] a) { return 0; }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { return 0; }
}
