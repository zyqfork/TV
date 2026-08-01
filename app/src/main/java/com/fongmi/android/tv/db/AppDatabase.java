package com.fongmi.android.tv.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.db.dao.ConfigDao;
import com.fongmi.android.tv.db.dao.DeviceDao;
import com.fongmi.android.tv.db.dao.HistoryDao;
import com.fongmi.android.tv.db.dao.KeepDao;
import com.fongmi.android.tv.db.dao.LiveDao;
import com.fongmi.android.tv.db.dao.SiteDao;
import com.fongmi.android.tv.db.dao.TrackDao;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Formatters;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Database(entities = {Keep.class, Site.class, Live.class, Track.class, Config.class, Device.class, History.class}, version = AppDatabase.VERSION)
public abstract class AppDatabase extends RoomDatabase {

    public static final int VERSION = 35;
    public static final String NAME = "tv";
    public static final String SYMBOL = "@@@";
    private static final int MAX_BACKUP = 5;

    private static volatile AppDatabase instance;

    public static synchronized AppDatabase get() {
        if (instance == null) instance = create(App.get());
        return instance;
    }

    public static void backup() {
        backup(new com.fongmi.android.tv.impl.Callback());
    }

    public static void backup(com.fongmi.android.tv.impl.Callback callback) {
        Task.execute(() -> {
            try {
                File dir = backupDir();
                File file = new File(dir, "tv-" + LocalDateTime.now().format(Formatters.BACKUP) + ".bk");
                Backup backup = Backup.create();
                if (backup.getConfig().isEmpty()) {
                    App.post(callback::error);
                    return;
                }
                Path.write(file, backup.toString().getBytes());
                if (!file.exists() || file.length() == 0) {
                    App.post(callback::error);
                    return;
                }
                FileUtil.gzipCompress(file);
                File gz = new File(file.getAbsolutePath() + ".gz");
                if (!gz.exists() || gz.length() == 0) {
                    App.post(callback::error);
                    return;
                }
                cleanOld();
                App.post(callback::success);
            } catch (Throwable e) {
                App.post(callback::error);
            }
        });
    }

    public static void restore(File file, com.fongmi.android.tv.impl.Callback callback) {
        Task.execute(() -> {
            try {
                File restore = Path.cache("restore");
                FileUtil.gzipDecompress(file, restore);
                Backup backup = Backup.objectFrom(Path.read(restore));
                if (backup.getConfig().isEmpty()) {
                    App.post(callback::error);
                } else {
                    backup.restore();
                    Path.clear(restore);
                    App.post(callback::success);
                }
            } catch (Throwable e) {
                App.post(callback::error);
            }
        });
    }

    /** Prefer /sdcard/TV when writable; otherwise app-specific backup dir. */
    public static File backupDir() {
        File preferred = Path.tv();
        if (canWrite(preferred)) return preferred;
        return Path.backup();
    }

    public static List<File> listBackups() {
        Map<String, File> map = new LinkedHashMap<>();
        addBackups(map, Path.tv());
        addBackups(map, Path.backup());
        List<File> items = new ArrayList<>(map.values());
        items.sort((f1, f2) -> Long.compare(f2.lastModified(), f1.lastModified()));
        return items;
    }

    private static void addBackups(Map<String, File> map, File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.startsWith("tv") && name.endsWith(".bk.gz")) map.put(file.getAbsolutePath(), file);
        }
    }

    private static boolean canWrite(File dir) {
        if (dir == null) return false;
        if (!dir.exists() && !dir.mkdirs()) return false;
        File probe = new File(dir, ".write_test");
        try {
            if (!probe.createNewFile() && !probe.exists()) return false;
            return probe.delete() || !probe.exists();
        } catch (IOException e) {
            return false;
        } finally {
            if (probe.exists()) probe.delete();
        }
    }

    private static void cleanOld() {
        List<File> items = listBackups();
        if (items.size() > MAX_BACKUP) for (int i = MAX_BACKUP; i < items.size(); i++) Path.clear(items.get(i));
    }

    private static AppDatabase create(Context context) {
        return Room.databaseBuilder(context, AppDatabase.class, NAME)
                .addMigrations(Migrations.MIGRATION_30_31)
                .addMigrations(Migrations.MIGRATION_31_32)
                .addMigrations(Migrations.MIGRATION_32_33)
                .addMigrations(Migrations.MIGRATION_33_34)
                .addMigrations(Migrations.MIGRATION_34_35)
                // Prefer failing closed over silent wipe when a migration path is missing.
                .allowMainThreadQueries().build();
    }

    public abstract KeepDao getKeepDao();

    public abstract SiteDao getSiteDao();

    public abstract LiveDao getLiveDao();

    public abstract TrackDao getTrackDao();

    public abstract ConfigDao getConfigDao();

    public abstract DeviceDao getDeviceDao();

    public abstract HistoryDao getHistoryDao();
}
