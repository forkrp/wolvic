package com.igalia.wolvic.utils;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.igalia.wolvic.downloads.Download;
import com.igalia.wolvic.downloads.DownloadJob;
import com.igalia.wolvic.downloads.DownloadsManager;
import com.igalia.wolvic.ui.widgets.WidgetManagerDelegate;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Objects;

public class DictionariesManager implements DownloadsManager.DownloadsListener {

    static final String LOGTAG = SystemUtils.createLogtag(DictionariesManager.class);

    private final Context mContext;
    private final DownloadsManager mDownloadManager;
    private long mDicDownloadLang = -1;

    public DictionariesManager(@NonNull Context context) {
        mContext = context;
        WidgetManagerDelegate mApplicationDelegate = ((WidgetManagerDelegate) context);
        mDownloadManager = mApplicationDelegate.getServicesProvider().getDownloadsManager();
    }

    public void init() {
        mDownloadManager.addListener(this);
    }

    public void end() {
        mDownloadManager.removeListener(this);
    }

    @Nullable
    public String getOrDownloadDictionary(@NonNull String lang) {
        String dictionaryPath = null;
        if (DictionaryUtils.isBuiltinDictionary(lang)) {
            dictionaryPath = DictionaryUtils.getBuiltinDicPath();

            for (String dbName : DictionaryUtils.getBuiltinDicNames(lang)) {
                if (!mContext.getDatabasePath(dbName).exists()) {
                    try {
                        InputStream in = mContext.getAssets().open(dictionaryPath + dbName);
                        storeDatabase(in, dbName);
                    } catch (Exception e) {
                        Log.e(LOGTAG, Objects.requireNonNull(e.getMessage()));
                    }
                }
            }
        } else if (DictionaryUtils.isExternalDictionary(mContext, lang)) {
            dictionaryPath = DictionaryUtils.getExternalDicPath(mContext, lang);
            if (dictionaryPath != null) return dictionaryPath;

            if (mDicDownloadLang != -1) {
                mDownloadManager.removeDownload(mDicDownloadLang, true);
            }

            downloadDictionary(lang);
        }

        return dictionaryPath;
    }

    private void downloadDictionary(@NonNull String lang) {
        final Dictionary dictionary = DictionaryUtils.getExternalDictionaryByLang(mContext, lang);
        final String payload = DictionaryUtils.getDictionaryPayload(dictionary);
        if (dictionary != null) {
            // Check if the dic is being downloaded or has been already downloaded.
            // The download item will be removed in 2 situations:
            //   1- the user selects another keyboard before the download is completed
            //   2- when the storing as database task is completed successfully.
            Download dicItem = mDownloadManager.getDownloads().stream().filter(item -> item.getUri().equals(payload)).findFirst().orElse(null);

            if (dicItem == null) {
                // If the dic has not been downloaded, start downloading it
                String outputPath = mContext.getExternalFilesDir(null).getAbsolutePath();
                DownloadJob job = DownloadJob.create(payload);
                job.setOutputPath(outputPath + "/" + job.getFilename());
                mDownloadManager.startDownload(job);
            } else if (dicItem.getStatus() == Download.SUCCESSFUL) {
                Log.w(LOGTAG, "The storing as database task failed for unknown reasons");
                mDicDownloadLang = -1;
                mDownloadManager.removeDownload(dicItem.getId(), true);
            } else {
                // The 'downloadDictionary' method is called either by 'getOrDownloadDictionary' when the user changes / type the keyboard
                Log.w(LOGTAG, "Download is still in progress; we shouldn't reach this code.");
            }
        }
    }

    private void storeDatabase(@NonNull InputStream inputStream, @NonNull String databaseName) {
        OutputStream outputStream;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                outputStream = Files.newOutputStream(mContext.getDatabasePath(databaseName).toPath());
            } else {
                outputStream = new FileOutputStream(mContext.getDatabasePath(databaseName));
            }

            byte[] buffer = new byte[1024 * 8];
            int numOfBytesToRead;
            while ((numOfBytesToRead = inputStream.read(buffer)) > 0)
                outputStream.write(buffer, 0, numOfBytesToRead);
            inputStream.close();
            outputStream.close();
        } catch (Exception e) {
            Log.e(LOGTAG, Objects.requireNonNull(e.getMessage()));
        }
    }

    // DownloadsManager
    @Override
    public void onDownloadsUpdate(@NonNull List<Download> downloads) {
        Download dicDownload = downloads.stream().filter(download -> DictionaryUtils.getExternalDictionaryByPayload(mContext, download.getUri()) != null).findFirst().orElse(null);
        if (dicDownload != null) {
            mDicDownloadLang = dicDownload.getId();
        }
    }

    @Override
    public void onDownloadCompleted(@NonNull Download download) {
        assert download.getStatus() == Download.SUCCESSFUL;
        if (download.getOutputFile() == null) {
            Log.w(LOGTAG, "Failed to download URI, missing input stream: " + download.getUri());
            return;
        }
        Dictionary dic = DictionaryUtils.getExternalDictionaryByPayload(mContext, download.getUri());
        if (dic != null) {
            mDicDownloadLang = -1;

            // Store as database
            try {
                InputStream in = new FileInputStream(download.getOutputFile());
                storeDatabase(in, DictionaryUtils.getExternalDicFullName(dic.getLang()));
            } catch (Exception e) {
                Log.e(LOGTAG, Objects.requireNonNull(e.getMessage()));
            }

            mDownloadManager.removeDownload(download.getId(), true);
        }
    }
}
