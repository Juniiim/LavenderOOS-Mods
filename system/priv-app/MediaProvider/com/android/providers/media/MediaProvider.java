package com.android.providers.media;

import android.app.AppGlobals;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.pm.PermissionGroupInfo;
import android.content.res.AssetFileDescriptor;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Icon;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.FileUtils;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.UserManager;
import android.os.storage.StorageEventListener;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.preference.PreferenceManager;
import android.provider.Column;
import android.provider.MediaStore;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.util.SparseArray;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.providers.media.MediaProvider;
import com.android.providers.media.scan.MediaScanner;
import com.android.providers.media.util.IsoInterface;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import libcore.io.IoUtils;
import libcore.util.EmptyArray;

public class MediaProvider extends ContentProvider {
    private static final int AUDIO_ALBUMART = 119;
    private static final int AUDIO_ALBUMART_FILE_ID = 121;
    private static final int AUDIO_ALBUMART_ID = 120;
    private static final int AUDIO_ALBUMS = 116;
    private static final int AUDIO_ALBUMS_ID = 117;
    private static final int AUDIO_ARTISTS = 114;
    private static final int AUDIO_ARTISTS_ID = 115;
    private static final int AUDIO_ARTISTS_ID_ALBUMS = 118;
    private static final int AUDIO_GENRES = 106;
    private static final int AUDIO_GENRES_ALL_MEMBERS = 109;
    private static final int AUDIO_GENRES_ID = 107;
    private static final int AUDIO_GENRES_ID_MEMBERS = 108;
    private static final int AUDIO_MEDIA = 100;
    private static final int AUDIO_MEDIA_ID = 101;
    private static final int AUDIO_MEDIA_ID_GENRES = 102;
    private static final int AUDIO_MEDIA_ID_GENRES_ID = 103;
    private static final int AUDIO_MEDIA_ID_PLAYLISTS = 104;
    private static final int AUDIO_MEDIA_ID_PLAYLISTS_ID = 105;
    private static final int AUDIO_PLAYLISTS = 110;
    private static final int AUDIO_PLAYLISTS_ID = 111;
    private static final int AUDIO_PLAYLISTS_ID_MEMBERS = 112;
    private static final int AUDIO_PLAYLISTS_ID_MEMBERS_ID = 113;
    private static final String CANONICAL = "canonical";
    static final boolean DBG = SystemProperties.getBoolean("persist.sys.assert.panic", LOCAL_LOGV);
    private static final int DOWNLOADS = 800;
    private static final int DOWNLOADS_ID = 801;
    private static final String EMULATED_PATH = "/storage/emulated/";
    public static final boolean ENABLE_MODERN_SCANNER = SystemProperties.getBoolean("persist.sys.modern_scanner", true);
    private static final String EXTERNAL_DATABASE_NAME = "external.db";
    private static final int FILES = 700;
    private static final int FILES_DIRECTORY = 706;
    private static final int FILES_ID = 701;
    private static final int FS_ID = 600;
    private static final String[] GENRE_LOOKUP_PROJECTION = {"_id", "name"};
    private static final UriMatcher HIDDEN_URI_MATCHER = new UriMatcher(-1);
    private static final int IDLE_CONNECTION_TIMEOUT_MS = 30000;
    private static final String ID_NOT_PARENT_CLAUSE = "_id NOT IN (SELECT parent FROM files)";
    private static final int IMAGES_MEDIA = 1;
    private static final int IMAGES_MEDIA_ID = 2;
    private static final int IMAGES_MEDIA_ID_THUMBNAIL = 3;
    private static final int IMAGES_THUMBNAILS = 4;
    private static final int IMAGES_THUMBNAILS_ID = 5;
    private static final String INTERNAL_DATABASE_NAME = "internal.db";
    static final boolean LOCAL_LOGV;
    private static final int MAX_EXTERNAL_DATABASES = 3;
    private static final int MEDIA_SCANNER = 500;
    private static final int MTP_OBJECTS = 702;
    private static final int MTP_OBJECTS_ID = 703;
    private static final int MTP_OBJECT_REFERENCES = 704;
    private static final String OBJECT_REFERENCES_QUERY = "SELECT audio_id FROM audio_playlists_map WHERE playlist_id=? ORDER BY play_order";
    private static final long OBSOLETE_DATABASE_DB = 5184000000L;
    private static final int OP_DEBUG = 54088;
    private static final String[] PATH_PROJECTION = {"_id", "_data"};
    private static final Pattern PATTERN_ANDROID_PATH = Pattern.compile("(?i)^/storage/[^/]+/(?:[0-9]+/)?Android/([^/]+)/.*");
    private static final Pattern PATTERN_OWNED_PATH = Pattern.compile("(?i)^/storage/[^/]+/(?:[0-9]+/)?Android/(?:data|media|obb|sandbox)/([^/]+)/.*");
    private static final Pattern PATTERN_RELATIVE_PATH = Pattern.compile("(?i)^/storage/[^/]+/(?:[0-9]+/)?(Android/sandbox/([^/]+)/)?");
    private static final Pattern PATTERN_SELECTION_ID = Pattern.compile("(?:image_id|video_id)\\s*=\\s*(\\d+)");
    private static final Pattern PATTERN_STORAGE_PATH = Pattern.compile("(?i)^/storage/[^/]+/(?:[0-9]+/)?");
    private static final Pattern PATTERN_VOLUME_NAME = Pattern.compile("(?i)^/storage/([^/]+)");
    private static final UriMatcher PUBLIC_URI_MATCHER = new UriMatcher(-1);
    private static final String[] REDACTED_EXIF_TAGS = {"GPSAltitude", "GPSAltitudeRef", "GPSAreaInformation", "GPSDOP", "GPSDateStamp", "GPSDestBearing", "GPSDestBearingRef", "GPSDestDistance", "GPSDestDistanceRef", "GPSDestLatitude", "GPSDestLatitudeRef", "GPSDestLongitude", "GPSDestLongitudeRef", "GPSDifferential", "GPSImgDirection", "GPSImgDirectionRef", "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSMapDatum", "GPSMeasureMode", "GPSProcessingMethod", "GPSSatellites", "GPSSpeed", "GPSSpeedRef", "GPSStatus", "GPSTimeStamp", "GPSTrack", "GPSTrackRef", "GPSVersionID"};
    private static final int[] REDACTED_ISO_BOXES = {IsoInterface.BOX_LOCI, IsoInterface.BOX_XYZ, IsoInterface.BOX_GPS, IsoInterface.BOX_GPS0};
    private static final String SYSTEM_PATH = "/system";
    static final String TAG = "MediaProvider";
    private static final int TYPE_DELETE = 2;
    private static final int TYPE_QUERY = 0;
    private static final int TYPE_UPDATE = 1;
    private static final int VERSION = 601;
    static final int VERSION_J = 509;
    static final int VERSION_K = 700;
    static final int VERSION_L = 700;
    static final int VERSION_M = 800;
    static final int VERSION_N = 800;
    static final int VERSION_O = 800;
    static final int VERSION_P = 900;
    static final int VERSION_Q = 1023;
    private static final int VIDEO_MEDIA = 200;
    private static final int VIDEO_MEDIA_ID = 201;
    private static final int VIDEO_MEDIA_ID_THUMBNAIL = 202;
    private static final int VIDEO_THUMBNAILS = 203;
    private static final int VIDEO_THUMBNAILS_ID = 204;
    private static final int VOLUMES = 300;
    private static final int VOLUMES_ID = 301;
    private static final String XATTR_UUID = "user.uuid";
    private static volatile long sBackgroundDelay = 0;
    private static final Object sCacheLock = new Object();
    @GuardedBy({"sCacheLock"})
    private static final Set<String> sCachedExternalVolumeNames = new ArraySet();
    @GuardedBy({"sCacheLock"})
    private static final Map<String, Collection<File>> sCachedVolumeScanPaths = new ArrayMap();
    @GuardedBy({"sCacheLock"})
    private static final List<VolumeInfo> sCachedVolumes = new ArrayList();
    private static final ArrayMap<String, Object> sDataColumns = new ArrayMap<>();
    private static final String[] sDataOnlyColumn = {"_data"};
    private static final String[] sDefaultFolderNames = {Environment.DIRECTORY_MUSIC, Environment.DIRECTORY_PODCASTS, Environment.DIRECTORY_RINGTONES, Environment.DIRECTORY_ALARMS, Environment.DIRECTORY_NOTIFICATIONS, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES, Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_DCIM};
    static final ArrayList<Pattern> sGreylist = new ArrayList<>();
    private static final String[] sIdDataColumn = {"_id", "_data"};
    private static final String[] sIdOnlyColumn = {"_id"};
    private static final String[] sMediaTableColumns = {"_id", "media_type"};
    private static final ArraySet<String> sMutableColumns = new ArraySet<>();
    private static final ArraySet<String> sPlacementColumns = new ArraySet<>();
    private static final String[] sPlaylistIdPlayOrder = {"playlist_id", "play_order"};
    @GuardedBy({"sProjectionMapCache"})
    private static final ArrayMap<Class<?>, ArrayMap<String, String>> sProjectionMapCache = new ArrayMap<>();
    private final AppOpsManager.OnOpActiveChangedListener mActiveListener = new AppOpsManager.OnOpActiveChangedListener() {
        /* class com.android.providers.media.$$Lambda$MediaProvider$Truz5caeyHQmgQCfXjSnuq6p2qg */

        public final void onOpActiveChanged(int i, int i2, String str, boolean z) {
            MediaProvider.this.lambda$new$0$MediaProvider(i, i2, str, z);
        }
    };
    private AppOpsManager mAppOpsManager;
    @GuardedBy({"mAttachedVolumeNames"})
    private final ArraySet<String> mAttachedVolumeNames = new ArraySet<>();
    private Thumbnailer mAudioThumbnailer = new Thumbnailer(Environment.DIRECTORY_MUSIC) {
        /* class com.android.providers.media.MediaProvider.AnonymousClass5 */

        @Override // com.android.providers.media.MediaProvider.Thumbnailer
        public Bitmap getThumbnailBitmap(Uri uri, CancellationSignal cancellationSignal) throws IOException {
            return ThumbnailUtils.createAudioThumbnail(MediaProvider.this.queryForDataFile(uri, cancellationSignal), MediaProvider.this.mThumbSize, cancellationSignal);
        }
    };
    @GuardedBy({"mCachedCallingIdentity"})
    private final SparseArray<LocalCallingIdentity> mCachedCallingIdentity = new SparseArray<>();
    private final ThreadLocal<LocalCallingIdentity> mCallingIdentity = ThreadLocal.withInitial(new Supplier() {
        /* class com.android.providers.media.$$Lambda$MediaProvider$RQOQ1sPV_0MTy4rAh_cdeRWm1k */

        @Override // java.util.function.Supplier
        public final Object get() {
            return MediaProvider.this.lambda$new$1$MediaProvider();
        }
    });
    @GuardedBy({"mDirectoryCache"})
    private final ArrayMap<String, Long> mDirectoryCache = new ArrayMap<>();
    private DatabaseHelper mExternalDatabase;
    private Thumbnailer mImageThumbnailer = new Thumbnailer(Environment.DIRECTORY_PICTURES) {
        /* class com.android.providers.media.MediaProvider.AnonymousClass7 */

        @Override // com.android.providers.media.MediaProvider.Thumbnailer
        public Bitmap getThumbnailBitmap(Uri uri, CancellationSignal cancellationSignal) throws IOException {
            return ThumbnailUtils.createImageThumbnail(MediaProvider.this.queryForDataFile(uri, cancellationSignal), MediaProvider.this.mThumbSize, cancellationSignal);
        }
    };
    private DatabaseHelper mInternalDatabase;
    private BroadcastReceiver mMediaReceiver = new BroadcastReceiver() {
        /* class com.android.providers.media.MediaProvider.AnonymousClass1 */

        /* JADX INFO: Can't fix incorrect switch cases order, some code will duplicate */
        /* JADX WARNING: Code restructure failed: missing block: B:22:0x0079, code lost:
            if (r2.equals("android.intent.action.MEDIA_MOUNTED") != false) goto L_0x0087;
         */
        /* Code decompiled incorrectly, please refer to instructions dump. */
        public void onReceive(android.content.Context r13, android.content.Intent r14) {
            /*
            // Method dump skipped, instructions count: 240
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.AnonymousClass1.onReceive(android.content.Context, android.content.Intent):void");
        }
    };
    private String mMediaScannerVolume;
    private final SQLiteDatabase.CustomFunction mObjectRemovedCallback = new SQLiteDatabase.CustomFunction() {
        /* class com.android.providers.media.MediaProvider.AnonymousClass2 */

        public void callback(String[] strArr) {
            synchronized (MediaProvider.this.mDirectoryCache) {
                MediaProvider.this.mDirectoryCache.clear();
            }
        }
    };
    private PackageManager mPackageManager;
    private int mRedactingCounts = 0;
    private StorageManager mStorageManager;
    private Size mThumbSize;
    private Thumbnailer mVideoThumbnailer = new Thumbnailer(Environment.DIRECTORY_MOVIES) {
        /* class com.android.providers.media.MediaProvider.AnonymousClass6 */

        @Override // com.android.providers.media.MediaProvider.Thumbnailer
        public Bitmap getThumbnailBitmap(Uri uri, CancellationSignal cancellationSignal) throws IOException {
            return ThumbnailUtils.createVideoThumbnail(MediaProvider.this.queryForDataFile(uri, cancellationSignal), MediaProvider.this.mThumbSize, cancellationSignal);
        }
    };
    private int mVolumeId = -1;

    public MediaProvider() {
        sDataColumns.put("_data", null);
        sDataColumns.put("_data", null);
        sDataColumns.put("_data", null);
        sDataColumns.put("_data", null);
        sDataColumns.put("album_art", null);
        sMutableColumns.add("_data");
        sMutableColumns.add("relative_path");
        sMutableColumns.add("_display_name");
        sMutableColumns.add("is_pending");
        sMutableColumns.add("is_trashed");
        sMutableColumns.add("date_expires");
        sMutableColumns.add("primary_directory");
        sMutableColumns.add("secondary_directory");
        sMutableColumns.add("bookmark");
        sMutableColumns.add("tags");
        sMutableColumns.add("category");
        sMutableColumns.add("bookmark");
        sMutableColumns.add("name");
        sMutableColumns.add("audio_id");
        sMutableColumns.add("play_order");
        sMutableColumns.add("mime_type");
        sMutableColumns.add("media_type");
        sPlacementColumns.add("_data");
        sPlacementColumns.add("relative_path");
        sPlacementColumns.add("_display_name");
        sPlacementColumns.add("mime_type");
        sPlacementColumns.add("primary_directory");
        sPlacementColumns.add("secondary_directory");
    }

    static {
        boolean z = false;
        if (Log.isLoggable(TAG, 2) || SystemProperties.getBoolean("persist.debug.mp.all", false)) {
            z = true;
        }
        LOCAL_LOGV = z;
        UriMatcher uriMatcher = PUBLIC_URI_MATCHER;
        UriMatcher uriMatcher2 = HIDDEN_URI_MATCHER;
        uriMatcher.addURI("media", "*/images/media", 1);
        uriMatcher.addURI("media", "*/images/media/#", 2);
        uriMatcher.addURI("media", "*/images/media/#/thumbnail", 3);
        uriMatcher.addURI("media", "*/images/thumbnails", 4);
        uriMatcher.addURI("media", "*/images/thumbnails/#", IMAGES_THUMBNAILS_ID);
        uriMatcher.addURI("media", "*/audio/media", AUDIO_MEDIA);
        uriMatcher.addURI("media", "*/audio/media/#", AUDIO_MEDIA_ID);
        uriMatcher.addURI("media", "*/audio/media/#/genres", AUDIO_MEDIA_ID_GENRES);
        uriMatcher.addURI("media", "*/audio/media/#/genres/#", AUDIO_MEDIA_ID_GENRES_ID);
        uriMatcher2.addURI("media", "*/audio/media/#/playlists", AUDIO_MEDIA_ID_PLAYLISTS);
        uriMatcher2.addURI("media", "*/audio/media/#/playlists/#", AUDIO_MEDIA_ID_PLAYLISTS_ID);
        uriMatcher.addURI("media", "*/audio/genres", AUDIO_GENRES);
        uriMatcher.addURI("media", "*/audio/genres/#", AUDIO_GENRES_ID);
        uriMatcher.addURI("media", "*/audio/genres/#/members", AUDIO_GENRES_ID_MEMBERS);
        uriMatcher.addURI("media", "*/audio/genres/all/members", AUDIO_GENRES_ALL_MEMBERS);
        uriMatcher.addURI("media", "*/audio/playlists", AUDIO_PLAYLISTS);
        uriMatcher.addURI("media", "*/audio/playlists/#", AUDIO_PLAYLISTS_ID);
        uriMatcher.addURI("media", "*/audio/playlists/#/members", AUDIO_PLAYLISTS_ID_MEMBERS);
        uriMatcher.addURI("media", "*/audio/playlists/#/members/#", AUDIO_PLAYLISTS_ID_MEMBERS_ID);
        uriMatcher.addURI("media", "*/audio/artists", AUDIO_ARTISTS);
        uriMatcher.addURI("media", "*/audio/artists/#", AUDIO_ARTISTS_ID);
        uriMatcher.addURI("media", "*/audio/artists/#/albums", AUDIO_ARTISTS_ID_ALBUMS);
        uriMatcher.addURI("media", "*/audio/albums", AUDIO_ALBUMS);
        uriMatcher.addURI("media", "*/audio/albums/#", AUDIO_ALBUMS_ID);
        uriMatcher.addURI("media", "*/audio/albumart", AUDIO_ALBUMART);
        uriMatcher.addURI("media", "*/audio/albumart/#", AUDIO_ALBUMART_ID);
        uriMatcher.addURI("media", "*/audio/media/#/albumart", AUDIO_ALBUMART_FILE_ID);
        uriMatcher.addURI("media", "*/video/media", VIDEO_MEDIA);
        uriMatcher.addURI("media", "*/video/media/#", VIDEO_MEDIA_ID);
        uriMatcher.addURI("media", "*/video/media/#/thumbnail", VIDEO_MEDIA_ID_THUMBNAIL);
        uriMatcher.addURI("media", "*/video/thumbnails", VIDEO_THUMBNAILS);
        uriMatcher.addURI("media", "*/video/thumbnails/#", VIDEO_THUMBNAILS_ID);
        uriMatcher.addURI("media", "*/media_scanner", MEDIA_SCANNER);
        uriMatcher.addURI("media", "*/fs_id", FS_ID);
        uriMatcher.addURI("media", "*/version", VERSION);
        uriMatcher2.addURI("media", "*", VOLUMES_ID);
        uriMatcher2.addURI("media", null, VOLUMES);
        uriMatcher.addURI("media", "*/file", 700);
        uriMatcher.addURI("media", "*/file/#", FILES_ID);
        uriMatcher2.addURI("media", "*/object", MTP_OBJECTS);
        uriMatcher2.addURI("media", "*/object/#", MTP_OBJECTS_ID);
        uriMatcher2.addURI("media", "*/object/#/references", MTP_OBJECT_REFERENCES);
        uriMatcher2.addURI("media", "*/dir", FILES_DIRECTORY);
        uriMatcher.addURI("media", "*/downloads", 800);
        uriMatcher.addURI("media", "*/downloads/#", DOWNLOADS_ID);
        uriMatcher.addURI("media", "*/op_debug", OP_DEBUG);
        addGreylistPattern("(?i)[_a-z0-9]+( (as )?[_a-z0-9]+)?");
        addGreylistPattern("audio\\._id AS _id");
        addGreylistPattern("(?i)(min|max|sum|avg|total|count|cast)\\(([_a-z0-9]+( (as )?[_a-z0-9]+)?|\\*)\\)( (as )?[_a-z0-9]+)?");
        addGreylistPattern("case when case when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added \\* \\d+ when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added / \\d+ else \\d+ end > case when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified \\* \\d+ when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified / \\d+ else \\d+ end then case when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added \\* \\d+ when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added / \\d+ else \\d+ end else case when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified \\* \\d+ when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified / \\d+ else \\d+ end end as corrected_added_modified");
        addGreylistPattern("MAX\\(case when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken \\* \\d+ when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken / \\d+ else \\d+ end\\)");
        addGreylistPattern("MAX\\(case when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added \\* \\d+ when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added when \\(date_added >= \\d+ and date_added < \\d+\\) then date_added / \\d+ else \\d+ end\\)");
        addGreylistPattern("MAX\\(case when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified \\* \\d+ when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified when \\(date_modified >= \\d+ and date_modified < \\d+\\) then date_modified / \\d+ else \\d+ end\\)");
        addGreylistPattern("\"content://media/[a-z]+/audio/media\"");
        addGreylistPattern("substr\\(_data, length\\(_data\\)-length\\(_display_name\\), 1\\) as filename_prevchar");
        addGreylistPattern("\\*( (as )?[_a-z0-9]+)?");
        addGreylistPattern("case when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken \\* \\d+ when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken when \\(datetaken >= \\d+ and datetaken < \\d+\\) then datetaken / \\d+ else \\d+ end");
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private void updateVolumes() {
        synchronized (sCacheLock) {
            sCachedVolumes.clear();
            sCachedVolumes.addAll(this.mStorageManager.getVolumes());
            sCachedExternalVolumeNames.clear();
            sCachedExternalVolumeNames.addAll(MediaStore.getExternalVolumeNames(getContext()));
            sCachedVolumeScanPaths.clear();
            try {
                sCachedVolumeScanPaths.put("internal", MediaStore.getVolumeScanPaths("internal"));
                for (String str : sCachedExternalVolumeNames) {
                    if (str.equals("external_primary")) {
                        if (DBG) {
                            Log.d(TAG, "updateVolumes MediaStore.getVolumeScanPaths(volumeName): " + str + " start");
                        }
                        Collection<File> volumeScanPaths = MediaStore.getVolumeScanPaths(str);
                        if (DBG) {
                            Log.d(TAG, "updateVolumes MediaStore.getVolumeScanPaths(volumeName): " + str + " end");
                        }
                        File file = new File(EMULATED_PATH, Integer.toString(999));
                        try {
                            volumeScanPaths.add(file.getCanonicalFile());
                        } catch (IOException e) {
                            Log.w(TAG, "Failed to resolve " + file + ": " + e);
                            volumeScanPaths.add(file);
                        }
                        sCachedVolumeScanPaths.put(str, volumeScanPaths);
                    }
                }
            } catch (FileNotFoundException e2) {
                throw new IllegalStateException(e2.getMessage());
            }
        }
    }

    public static File getVolumePath(String str) throws FileNotFoundException {
        File volumePath;
        synchronized (sCacheLock) {
            volumePath = MediaStore.getVolumePath(sCachedVolumes, str);
        }
        return volumePath;
    }

    public static Set<String> getExternalVolumeNames() {
        ArraySet arraySet;
        synchronized (sCacheLock) {
            arraySet = new ArraySet(sCachedExternalVolumeNames);
        }
        return arraySet;
    }

    public static Collection<File> getVolumeScanPaths(String str) {
        ArrayList arrayList;
        synchronized (sCacheLock) {
            arrayList = new ArrayList(sCachedVolumeScanPaths.get(str));
        }
        return arrayList;
    }

    public /* synthetic */ void lambda$new$0$MediaProvider(int i, int i2, String str, boolean z) {
        synchronized (this.mCachedCallingIdentity) {
            if (z) {
                this.mCachedCallingIdentity.put(i2, LocalCallingIdentity.fromExternal(i2, str));
            } else {
                this.mCachedCallingIdentity.remove(i2);
            }
            if (this.mCachedCallingIdentity.size() > 0) {
                sBackgroundDelay = 10000;
            } else {
                sBackgroundDelay = 0;
            }
        }
    }

    public /* synthetic */ LocalCallingIdentity lambda$new$1$MediaProvider() {
        LocalCallingIdentity localCallingIdentity;
        synchronized (this.mCachedCallingIdentity) {
            localCallingIdentity = this.mCachedCallingIdentity.get(Binder.getCallingUid());
            if (localCallingIdentity == null) {
                localCallingIdentity = LocalCallingIdentity.fromBinder(this);
            }
        }
        return localCallingIdentity;
    }

    /* access modifiers changed from: package-private */
    public static class DatabaseHelper extends SQLiteOpenHelper implements AutoCloseable {
        ArrayMap<String, Long> mAlbumCache;
        ArrayMap<String, Long> mArtistCache;
        final Context mContext;
        final boolean mEarlyUpgrade;
        final boolean mInternal;
        final String mName;
        private final ThreadLocal<List<Uri>> mNotifyChanges;
        final SQLiteDatabase.CustomFunction mObjectRemovedCallback;
        long mScanStartTime;
        long mScanStopTime;
        final int mVersion;

        public DatabaseHelper(Context context, String str, boolean z, boolean z2, SQLiteDatabase.CustomFunction customFunction) {
            this(context, str, MediaProvider.getDatabaseVersion(context), z, z2, customFunction);
        }

        public DatabaseHelper(Context context, String str, int i, boolean z, boolean z2, SQLiteDatabase.CustomFunction customFunction) {
            super(context, str, (SQLiteDatabase.CursorFactory) null, i);
            this.mArtistCache = new ArrayMap<>();
            this.mAlbumCache = new ArrayMap<>();
            this.mNotifyChanges = new ThreadLocal<>();
            this.mContext = context;
            this.mName = str;
            this.mVersion = i;
            this.mInternal = z;
            this.mEarlyUpgrade = z2;
            this.mObjectRemovedCallback = customFunction;
            setWriteAheadLoggingEnabled(true);
            setIdleConnectionTimeout(30000);
        }

        public void onCreate(SQLiteDatabase sQLiteDatabase) {
            Log.v(MediaProvider.TAG, "onCreate() for " + this.mName);
            MediaProvider.updateDatabase(this.mContext, sQLiteDatabase, this.mInternal, 0, this.mVersion);
        }

        public void onUpgrade(SQLiteDatabase sQLiteDatabase, int i, int i2) {
            Log.v(MediaProvider.TAG, "onUpgrade() for " + this.mName + " from " + i + " to " + i2);
            MediaProvider.updateDatabase(this.mContext, sQLiteDatabase, this.mInternal, i, i2);
        }

        public void onDowngrade(SQLiteDatabase sQLiteDatabase, int i, int i2) {
            Log.v(MediaProvider.TAG, "onDowngrade() for " + this.mName + " from " + i + " to " + i2);
            MediaProvider.downgradeDatabase(this.mContext, sQLiteDatabase, this.mInternal, i, i2);
        }

        public void onOpen(SQLiteDatabase sQLiteDatabase) {
            if (!this.mEarlyUpgrade) {
                SQLiteDatabase.CustomFunction customFunction = this.mObjectRemovedCallback;
                if (customFunction != null) {
                    sQLiteDatabase.addCustomFunction("_OBJECT_REMOVED", 1, customFunction);
                }
                if (!this.mInternal && Environment.isExternalStorageRemovable()) {
                    File file = new File(sQLiteDatabase.getPath());
                    long currentTimeMillis = System.currentTimeMillis();
                    file.setLastModified(currentTimeMillis);
                    String[] databaseList = this.mContext.databaseList();
                    ArrayList arrayList = new ArrayList();
                    int i = 0;
                    for (String str : databaseList) {
                        if (str != null && str.endsWith(".db")) {
                            arrayList.add(str);
                        }
                    }
                    String[] strArr = (String[]) arrayList.toArray(new String[0]);
                    int length = strArr.length;
                    long j = currentTimeMillis - MediaProvider.OBSOLETE_DATABASE_DB;
                    int i2 = 3;
                    int i3 = length;
                    for (int i4 = 0; i4 < strArr.length; i4++) {
                        File databasePath = this.mContext.getDatabasePath(strArr[i4]);
                        if (MediaProvider.INTERNAL_DATABASE_NAME.equals(strArr[i4]) || file.equals(databasePath)) {
                            strArr[i4] = null;
                            i3--;
                            if (file.equals(databasePath)) {
                                i2--;
                            }
                        } else if (databasePath.lastModified() < j) {
                            if (MediaProvider.LOCAL_LOGV) {
                                Log.v(MediaProvider.TAG, "Deleting old database " + strArr[i4]);
                            }
                            this.mContext.deleteDatabase(strArr[i4]);
                            strArr[i4] = null;
                            i3--;
                        }
                    }
                    while (i3 > i2) {
                        long j2 = 0;
                        int i5 = -1;
                        for (int i6 = i; i6 < strArr.length; i6++) {
                            if (strArr[i6] != null) {
                                long lastModified = this.mContext.getDatabasePath(strArr[i6]).lastModified();
                                if (j2 == 0 || lastModified < j2) {
                                    i5 = i6;
                                    j2 = lastModified;
                                }
                            }
                        }
                        if (i5 != -1) {
                            if (MediaProvider.LOCAL_LOGV) {
                                Log.v(MediaProvider.TAG, "Deleting old database " + strArr[i5]);
                            }
                            this.mContext.deleteDatabase(strArr[i5]);
                            strArr[i5] = null;
                            i3--;
                        }
                        i = 0;
                    }
                }
            }
        }

        public void beginTransaction() {
            getWritableDatabase().beginTransaction();
            this.mNotifyChanges.set(new ArrayList());
        }

        public void setTransactionSuccessful() {
            getWritableDatabase().setTransactionSuccessful();
            List<Uri> list = this.mNotifyChanges.get();
            if (list != null) {
                BackgroundThread.getHandler().postDelayed(new Runnable(list) {
                    /* class com.android.providers.media.$$Lambda$MediaProvider$DatabaseHelper$Nu9yi6Mt5jIJ5vOy7BmDFARARA */
                    private final /* synthetic */ List f$1;

                    {
                        this.f$1 = r2;
                    }

                    public final void run() {
                        MediaProvider.DatabaseHelper.this.lambda$setTransactionSuccessful$0$MediaProvider$DatabaseHelper(this.f$1);
                    }
                }, MediaProvider.sBackgroundDelay);
            }
            this.mNotifyChanges.remove();
        }

        public /* synthetic */ void lambda$setTransactionSuccessful$0$MediaProvider$DatabaseHelper(List list) {
            Iterator it = list.iterator();
            while (it.hasNext()) {
                lambda$notifyChange$1$MediaProvider$DatabaseHelper((Uri) it.next());
            }
        }

        public void endTransaction() {
            getWritableDatabase().endTransaction();
        }

        public void notifyChange(Uri uri) {
            if (MediaProvider.LOCAL_LOGV) {
                Log.v(MediaProvider.TAG, "Notifying " + uri);
            }
            List<Uri> list = this.mNotifyChanges.get();
            if (list != null) {
                list.add(uri);
            } else {
                BackgroundThread.getHandler().postDelayed(new Runnable(uri) {
                    /* class com.android.providers.media.$$Lambda$MediaProvider$DatabaseHelper$BBMCnGzGlOAefxgK2PyZnzYayU */
                    private final /* synthetic */ Uri f$1;

                    {
                        this.f$1 = r2;
                    }

                    public final void run() {
                        MediaProvider.DatabaseHelper.this.lambda$notifyChange$1$MediaProvider$DatabaseHelper(this.f$1);
                    }
                }, MediaProvider.sBackgroundDelay);
            }
        }

        /* access modifiers changed from: private */
        /* renamed from: notifyChangeInternal */
        public void lambda$notifyChange$1$MediaProvider$DatabaseHelper(Uri uri) {
            Trace.traceBegin(1048576, "notifyChange");
            try {
                this.mContext.getContentResolver().notifyChange(uri, null);
            } finally {
                Trace.traceEnd(1048576);
            }
        }
    }

    public static void acceptWithExpansion(Consumer<Uri> consumer, Uri uri) {
        int matchUri = matchUri(uri, true);
        acceptWithExpansionInternal(consumer, uri, matchUri);
        try {
            String volumeName = MediaStore.getVolumeName(uri);
            char c = 65535;
            int hashCode = volumeName.hashCode();
            if (hashCode != -1820761141) {
                if (hashCode == 570410685 && volumeName.equals("internal")) {
                    c = 0;
                }
            } else if (volumeName.equals("external")) {
                c = 1;
            }
            if (!(c == 0 || c == 1)) {
                ArrayList<String> arrayList = new ArrayList(uri.getPathSegments());
                arrayList.set(0, "external");
                Uri.Builder path = uri.buildUpon().path(null);
                for (String str : arrayList) {
                    path.appendPath(str);
                }
                acceptWithExpansionInternal(consumer, path.build(), matchUri);
            }
        } catch (IllegalArgumentException unused) {
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:17:0x0027, code lost:
        if (r8 != com.android.providers.media.MediaProvider.DOWNLOADS_ID) goto L_0x008b;
     */
    /* JADX WARNING: Removed duplicated region for block: B:23:0x008d A[ADDED_TO_REGION] */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private static void acceptWithExpansionInternal(java.util.function.Consumer<android.net.Uri> r6, android.net.Uri r7, int r8) {
        /*
        // Method dump skipped, instructions count: 177
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.acceptWithExpansionInternal(java.util.function.Consumer, android.net.Uri, int):void");
    }

    private static void deleteLegacyThumbnailData() {
        File[] defeatNullable = ArrayUtils.defeatNullable(new File(Environment.getExternalStorageDirectory(), "/DCIM/.thumbnails").listFiles($$Lambda$MediaProvider$U2k97ajc155duFYOmVbkZapXmeM.INSTANCE));
        for (File file : defeatNullable) {
            if (!file.delete()) {
                Log.e(TAG, "Failed to delete legacy thumbnail data " + file.getAbsolutePath());
            }
        }
    }

    private void ensureDefaultFolders(String str, DatabaseHelper databaseHelper, SQLiteDatabase sQLiteDatabase) {
        String str2;
        try {
            StorageVolume storageVolume = this.mStorageManager.getStorageVolume(getVolumePath(str));
            if ("emulated".equals(storageVolume.getId())) {
                str2 = "created_default_folders";
            } else {
                str2 = "created_default_folders_" + storageVolume.getNormalizedUuid();
            }
            SharedPreferences defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
            int i = 0;
            if (defaultSharedPreferences.getInt(str2, 0) == 0) {
                String[] strArr = sDefaultFolderNames;
                int length = strArr.length;
                while (i < length) {
                    File file = new File(storageVolume.getPathFile(), strArr[i]);
                    if (!file.exists()) {
                        file.mkdirs();
                        insertDirectory(databaseHelper, sQLiteDatabase, file.getAbsolutePath());
                    } else {
                        MediaProviderUtils.confirmDirectoryType(databaseHelper, sQLiteDatabase, file.getAbsolutePath());
                    }
                    i++;
                }
                SharedPreferences.Editor edit = defaultSharedPreferences.edit();
                edit.putInt(str2, 1);
                edit.commit();
                return;
            }
            String[] strArr2 = sDefaultFolderNames;
            int length2 = strArr2.length;
            while (i < length2) {
                File file2 = new File(storageVolume.getPathFile(), strArr2[i]);
                if (file2.exists() && file2.isDirectory()) {
                    MediaProviderUtils.confirmDirectoryType(databaseHelper, sQLiteDatabase, file2.getAbsolutePath());
                }
                i++;
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to ensure default folders for " + str, e);
        }
    }

    public static int getDatabaseVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException unused) {
            throw new RuntimeException("couldn't get version code for " + context);
        }
    }

    public boolean onCreate() {
        if (DBG) {
            Log.d(TAG, "onCreate start");
        }
        Context context = getContext();
        if (DBG) {
            Log.d(TAG, "setTransportLoggingEnabled start");
        }
        setTransportLoggingEnabled(LOCAL_LOGV);
        if (DBG) {
            Log.d(TAG, "setTransportLoggingEnabled end");
        }
        if (DBG) {
            Log.d(TAG, "Binder.setProxyTransactListener start");
        }
        Binder.setProxyTransactListener(new Binder.PropagateWorkSourceTransactListener());
        if (DBG) {
            Log.d(TAG, "Binder.setProxyTransactListener end");
        }
        this.mStorageManager = (StorageManager) context.getSystemService(StorageManager.class);
        this.mAppOpsManager = (AppOpsManager) context.getSystemService(AppOpsManager.class);
        this.mPackageManager = context.getPackageManager();
        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        int min = Math.min(displayMetrics.widthPixels, displayMetrics.heightPixels) / 2;
        this.mThumbSize = new Size(min, min);
        if (DBG) {
            Log.d(TAG, "new database helper start");
        }
        this.mInternalDatabase = new DatabaseHelper(context, INTERNAL_DATABASE_NAME, true, false, this.mObjectRemovedCallback);
        this.mExternalDatabase = new DatabaseHelper(context, EXTERNAL_DATABASE_NAME, false, false, this.mObjectRemovedCallback);
        if (DBG) {
            Log.d(TAG, "new database helper end");
        }
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.setPriority(10);
        intentFilter.addDataScheme("file");
        intentFilter.addAction("android.intent.action.MEDIA_UNMOUNTED");
        intentFilter.addAction("android.intent.action.MEDIA_MOUNTED");
        intentFilter.addAction("android.intent.action.MEDIA_EJECT");
        intentFilter.addAction("android.intent.action.MEDIA_REMOVED");
        intentFilter.addAction("android.intent.action.MEDIA_BAD_REMOVAL");
        context.registerReceiver(this.mMediaReceiver, intentFilter);
        if (DBG) {
            Log.d(TAG, "mStorageManager.registerListener start");
        }
        this.mStorageManager.registerListener(new StorageEventListener() {
            /* class com.android.providers.media.MediaProvider.AnonymousClass3 */

            public void onVolumeStateChanged(VolumeInfo volumeInfo, int i, int i2) {
                MediaProvider.this.updateVolumes();
            }
        });
        if (DBG) {
            Log.d(TAG, "mStorageManager.registerListener end");
        }
        if (DBG) {
            Log.d(TAG, "updateVolumes start");
        }
        updateVolumes();
        if (DBG) {
            Log.d(TAG, "updateVolumes end");
        }
        if (DBG) {
            Log.d(TAG, "attachVolume(MediaStore.VOLUME_INTERNAL); start");
        }
        attachVolume("internal");
        if (DBG) {
            Log.d(TAG, "attachVolume(MediaStore.VOLUME_INTERNAL); end");
        }
        if (DBG) {
            Log.d(TAG, "attachVolume(volumeName); start");
        }
        for (String str : getExternalVolumeNames()) {
            attachVolume(str);
        }
        if (DBG) {
            Log.d(TAG, "attachVolume(volumeName); end");
        }
        if (DBG) {
            Log.d(TAG, "mAppOpsManager.startWatchingActive start");
        }
        this.mAppOpsManager.startWatchingActive(new int[]{26}, this.mActiveListener);
        if (DBG) {
            Log.d(TAG, "mAppOpsManager.startWatchingActive end");
        }
        if (DBG) {
            Log.d(TAG, "onCreate() end");
        }
        return true;
    }

    public void onCallingPackageChanged() {
        this.mCallingIdentity.remove();
    }

    public LocalCallingIdentity clearLocalCallingIdentity() {
        LocalCallingIdentity localCallingIdentity = this.mCallingIdentity.get();
        this.mCallingIdentity.set(LocalCallingIdentity.fromSelf());
        return localCallingIdentity;
    }

    public void restoreLocalCallingIdentity(LocalCallingIdentity localCallingIdentity) {
        this.mCallingIdentity.set(localCallingIdentity);
    }

    /* JADX WARNING: Code restructure failed: missing block: B:52:0x01ab, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:54:0x01ad, code lost:
        if (r3 != null) goto L_0x01af;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:55:0x01af, code lost:
        $closeResource(r0, r3);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:56:0x01b2, code lost:
        throw r0;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:61:0x01b6, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:63:0x01b8, code lost:
        if (r3 != null) goto L_0x01ba;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:64:0x01ba, code lost:
        $closeResource(r0, r3);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:65:0x01bd, code lost:
        throw r0;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public void onIdleMaintenance(android.os.CancellationSignal r20) {
        /*
        // Method dump skipped, instructions count: 446
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.onIdleMaintenance(android.os.CancellationSignal):void");
    }

    private static /* synthetic */ void $closeResource(Throwable th, AutoCloseable autoCloseable) {
        if (th != null) {
            try {
                autoCloseable.close();
            } catch (Throwable th2) {
                th.addSuppressed(th2);
            }
        } else {
            autoCloseable.close();
        }
    }

    public void onPackageOrphaned(String str) {
        DatabaseHelper databaseHelper = this.mExternalDatabase;
        SQLiteDatabase writableDatabase = databaseHelper.getWritableDatabase();
        ContentValues contentValues = new ContentValues();
        contentValues.putNull("owner_package_name");
        int update = writableDatabase.update("files", contentValues, "owner_package_name=?", new String[]{str});
        if (update > 0) {
            Log.d(TAG, "Orphaned " + update + " items belonging to " + str + " on " + databaseHelper.mName);
        }
    }

    private void enforceShellRestrictions() {
        if (UserHandle.getCallingAppId() == 2000 && ((UserManager) getContext().getSystemService(UserManager.class)).hasUserRestriction("no_usb_file_transfer")) {
            throw new SecurityException("Shell user cannot access files for user " + UserHandle.myUserId());
        }
    }

    /* access modifiers changed from: protected */
    public int enforceReadPermissionInner(Uri uri, String str, IBinder iBinder) throws SecurityException {
        enforceShellRestrictions();
        return super.enforceReadPermissionInner(uri, str, iBinder);
    }

    /* access modifiers changed from: protected */
    public int enforceWritePermissionInner(Uri uri, String str, IBinder iBinder) throws SecurityException {
        enforceShellRestrictions();
        return super.enforceWritePermissionInner(uri, str, iBinder);
    }

    @VisibleForTesting
    static void makePristineSchema(SQLiteDatabase sQLiteDatabase) {
        Cursor query = sQLiteDatabase.query("sqlite_master", new String[]{"name"}, "type is 'trigger'", null, null, null, null);
        while (query.moveToNext()) {
            if (!query.getString(0).startsWith("sqlite_")) {
                sQLiteDatabase.execSQL("DROP TRIGGER IF EXISTS " + query.getString(0));
            }
        }
        query.close();
        Cursor query2 = sQLiteDatabase.query("sqlite_master", new String[]{"name"}, "type is 'view'", null, null, null, null);
        while (query2.moveToNext()) {
            if (!query2.getString(0).startsWith("sqlite_")) {
                sQLiteDatabase.execSQL("DROP VIEW IF EXISTS " + query2.getString(0));
            }
        }
        query2.close();
        Cursor query3 = sQLiteDatabase.query("sqlite_master", new String[]{"name"}, "type is 'index'", null, null, null, null);
        while (query3.moveToNext()) {
            if (!query3.getString(0).startsWith("sqlite_")) {
                sQLiteDatabase.execSQL("DROP INDEX IF EXISTS " + query3.getString(0));
            }
        }
        query3.close();
        Cursor query4 = sQLiteDatabase.query("sqlite_master", new String[]{"name"}, "type is 'table'", null, null, null, null);
        while (query4.moveToNext()) {
            if (!query4.getString(0).startsWith("sqlite_")) {
                sQLiteDatabase.execSQL("DROP TABLE IF EXISTS " + query4.getString(0));
            }
        }
        query4.close();
    }

    private static void createLatestSchema(SQLiteDatabase sQLiteDatabase, boolean z) {
        AppGlobals.getInitialApplication().revokeUriPermission(MediaStore.AUTHORITY_URI, 3);
        MediaDocumentsProvider.revokeAllUriGrants(AppGlobals.getInitialApplication());
        BackgroundThread.getHandler().post($$Lambda$MediaProvider$zwqYorahwz9cSWratn5aFT2yQKA.INSTANCE);
        makePristineSchema(sQLiteDatabase);
        sQLiteDatabase.execSQL("CREATE TABLE android_metadata (locale TEXT)");
        sQLiteDatabase.execSQL("CREATE TABLE thumbnails (_id INTEGER PRIMARY KEY,_data TEXT,image_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        sQLiteDatabase.execSQL("CREATE TABLE artists (artist_id INTEGER PRIMARY KEY,artist_key TEXT NOT NULL UNIQUE,artist TEXT NOT NULL)");
        sQLiteDatabase.execSQL("CREATE TABLE albums (album_id INTEGER PRIMARY KEY,album_key TEXT NOT NULL UNIQUE,album TEXT NOT NULL)");
        sQLiteDatabase.execSQL("CREATE TABLE album_art (album_id INTEGER PRIMARY KEY,_data TEXT)");
        sQLiteDatabase.execSQL("CREATE TABLE videothumbnails (_id INTEGER PRIMARY KEY,_data TEXT,video_id INTEGER,kind INTEGER,width INTEGER,height INTEGER)");
        sQLiteDatabase.execSQL("CREATE TABLE files (_id INTEGER PRIMARY KEY AUTOINCREMENT,_data TEXT UNIQUE COLLATE NOCASE,_size INTEGER,format INTEGER,parent INTEGER,date_added INTEGER,date_modified INTEGER,mime_type TEXT,title TEXT,description TEXT,_display_name TEXT,picasa_id TEXT,orientation INTEGER,latitude DOUBLE,longitude DOUBLE,datetaken INTEGER,mini_thumb_magic INTEGER,bucket_id TEXT,bucket_display_name TEXT,isprivate INTEGER,title_key TEXT,artist_id INTEGER,album_id INTEGER,composer TEXT,track INTEGER,year INTEGER CHECK(year!=0),is_ringtone INTEGER,is_music INTEGER,is_alarm INTEGER,is_notification INTEGER,is_podcast INTEGER,album_artist TEXT,duration INTEGER,bookmark INTEGER,artist TEXT,album TEXT,resolution TEXT,tags TEXT,category TEXT,language TEXT,mini_thumb_data TEXT,name TEXT,media_type INTEGER,old_id INTEGER,is_drm INTEGER,width INTEGER, height INTEGER, title_resource_uri TEXT,owner_package_name TEXT DEFAULT NULL,color_standard INTEGER, color_transfer INTEGER, color_range INTEGER,_hash BLOB DEFAULT NULL, is_pending INTEGER DEFAULT 0,is_download INTEGER DEFAULT 0, download_uri TEXT DEFAULT NULL,referer_uri TEXT DEFAULT NULL, is_audiobook INTEGER DEFAULT 0,date_expires INTEGER DEFAULT NULL,is_trashed INTEGER DEFAULT 0,group_id INTEGER DEFAULT NULL,primary_directory TEXT DEFAULT NULL,secondary_directory TEXT DEFAULT NULL,document_id TEXT DEFAULT NULL,instance_id TEXT DEFAULT NULL,original_document_id TEXT DEFAULT NULL,relative_path TEXT DEFAULT NULL,volume_name TEXT DEFAULT NULL)");
        sQLiteDatabase.execSQL("CREATE TABLE log (time DATETIME, message TEXT)");
        if (!z) {
            sQLiteDatabase.execSQL("CREATE TABLE audio_genres (_id INTEGER PRIMARY KEY,name TEXT NOT NULL)");
            sQLiteDatabase.execSQL("CREATE TABLE audio_genres_map (_id INTEGER PRIMARY KEY,audio_id INTEGER NOT NULL,genre_id INTEGER NOT NULL,UNIQUE (audio_id,genre_id) ON CONFLICT IGNORE)");
            sQLiteDatabase.execSQL("CREATE TABLE audio_playlists_map (_id INTEGER PRIMARY KEY,audio_id INTEGER NOT NULL,playlist_id INTEGER NOT NULL,play_order INTEGER NOT NULL)");
            sQLiteDatabase.execSQL("CREATE TRIGGER audio_genres_cleanup DELETE ON audio_genres BEGIN DELETE FROM audio_genres_map WHERE genre_id = old._id;END");
            sQLiteDatabase.execSQL("CREATE TRIGGER audio_playlists_cleanup DELETE ON files WHEN old.media_type=4 BEGIN DELETE FROM audio_playlists_map WHERE playlist_id = old._id;SELECT _DELETE_FILE(old._data);END");
            sQLiteDatabase.execSQL("CREATE TRIGGER files_cleanup DELETE ON files BEGIN SELECT _OBJECT_REMOVED(old._id);END");
        }
        sQLiteDatabase.execSQL("CREATE INDEX image_id_index on thumbnails(image_id)");
        sQLiteDatabase.execSQL("CREATE INDEX album_idx on albums(album)");
        sQLiteDatabase.execSQL("CREATE INDEX albumkey_index on albums(album_key)");
        sQLiteDatabase.execSQL("CREATE INDEX artist_idx on artists(artist)");
        sQLiteDatabase.execSQL("CREATE INDEX artistkey_index on artists(artist_key)");
        sQLiteDatabase.execSQL("CREATE INDEX video_id_index on videothumbnails(video_id)");
        sQLiteDatabase.execSQL("CREATE INDEX album_id_idx ON files(album_id)");
        sQLiteDatabase.execSQL("CREATE INDEX artist_id_idx ON files(artist_id)");
        sQLiteDatabase.execSQL("CREATE INDEX bucket_index on files(bucket_id,media_type,datetaken, _id)");
        sQLiteDatabase.execSQL("CREATE INDEX bucket_name on files(bucket_id,media_type,bucket_display_name)");
        sQLiteDatabase.execSQL("CREATE INDEX format_index ON files(format)");
        sQLiteDatabase.execSQL("CREATE INDEX media_type_index ON files(media_type)");
        sQLiteDatabase.execSQL("CREATE INDEX parent_index ON files(parent)");
        sQLiteDatabase.execSQL("CREATE INDEX path_index ON files(_data)");
        sQLiteDatabase.execSQL("CREATE INDEX sort_index ON files(datetaken ASC, _id ASC)");
        sQLiteDatabase.execSQL("CREATE INDEX title_idx ON files(title)");
        sQLiteDatabase.execSQL("CREATE INDEX titlekey_index ON files(title_key)");
        sQLiteDatabase.execSQL("CREATE TRIGGER albumart_cleanup1 DELETE ON albums BEGIN DELETE FROM album_art WHERE album_id = old.album_id;END");
        sQLiteDatabase.execSQL("CREATE TRIGGER albumart_cleanup2 DELETE ON album_art BEGIN SELECT _DELETE_FILE(old._data);END");
        createLatestViews(sQLiteDatabase, z);
    }

    /* JADX WARNING: Code restructure failed: missing block: B:15:0x002b, code lost:
        r2 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:16:0x002c, code lost:
        if (r0 != null) goto L_0x002e;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:17:0x002e, code lost:
        $closeResource(r1, r0);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:18:0x0031, code lost:
        throw r2;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    static /* synthetic */ void lambda$createLatestSchema$3() {
        /*
            android.app.Application r0 = android.app.AppGlobals.getInitialApplication()     // Catch:{ RemoteException -> 0x0032 }
            android.content.ContentResolver r0 = r0.getContentResolver()     // Catch:{ RemoteException -> 0x0032 }
            java.lang.String r1 = "downloads"
            android.content.ContentProviderClient r0 = r0.acquireContentProviderClient(r1)     // Catch:{ RemoteException -> 0x0032 }
            r1 = 0
            if (r0 != 0) goto L_0x001e
            java.lang.String r2 = "MediaProvider"
            java.lang.String r3 = "Skip createLatestSchema while client invalid"
            android.util.Log.v(r2, r3)     // Catch:{ all -> 0x0029 }
            if (r0 == 0) goto L_0x001d
            $closeResource(r1, r0)
        L_0x001d:
            return
        L_0x001e:
            java.lang.String r2 = "revoke_mediastore_uri_perms"
            r0.call(r2, r1, r1)
            if (r0 == 0) goto L_0x0032
            $closeResource(r1, r0)
            goto L_0x0032
        L_0x0029:
            r1 = move-exception
            throw r1     // Catch:{ all -> 0x002b }
        L_0x002b:
            r2 = move-exception
            if (r0 == 0) goto L_0x0031
            $closeResource(r1, r0)
        L_0x0031:
            throw r2
        L_0x0032:
            return
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.lambda$createLatestSchema$3():void");
    }

    private static void makePristineViews(SQLiteDatabase sQLiteDatabase) {
        Cursor query = sQLiteDatabase.query("sqlite_master", new String[]{"name"}, "type is 'view'", null, null, null, null);
        while (query.moveToNext()) {
            sQLiteDatabase.execSQL("DROP VIEW IF EXISTS " + query.getString(0));
        }
        query.close();
    }

    private static void createLatestViews(SQLiteDatabase sQLiteDatabase, boolean z) {
        makePristineViews(sQLiteDatabase);
        if (!z) {
            sQLiteDatabase.execSQL("CREATE VIEW audio_playlists AS SELECT _id,_data,name,date_added,date_modified,owner_package_name,_hash,is_pending,date_expires,is_trashed,volume_name FROM files WHERE media_type=4");
        }
        sQLiteDatabase.execSQL("CREATE VIEW audio_meta AS SELECT _id,_data,_display_name,_size,mime_type,date_added,is_drm,date_modified,title,title_key,duration,artist_id,composer,album_id,track,year,is_ringtone,is_music,is_alarm,is_notification,is_podcast,bookmark,album_artist,owner_package_name,_hash,is_pending,is_audiobook,date_expires,is_trashed,group_id,primary_directory,secondary_directory,document_id,instance_id,original_document_id,title_resource_uri,relative_path,volume_name,datetaken,bucket_id,bucket_display_name,group_id,orientation FROM files WHERE media_type=2");
        sQLiteDatabase.execSQL("CREATE VIEW artists_albums_map AS SELECT DISTINCT artist_id, album_id FROM audio_meta");
        sQLiteDatabase.execSQL("CREATE VIEW audio as SELECT *, NULL AS width, NULL as height FROM audio_meta LEFT OUTER JOIN artists ON audio_meta.artist_id=artists.artist_id LEFT OUTER JOIN albums ON audio_meta.album_id=albums.album_id");
        sQLiteDatabase.execSQL("CREATE VIEW album_info AS SELECT audio.album_id AS _id, album, album_key, MIN(year) AS minyear, MAX(year) AS maxyear, artist, artist_id, artist_key, count(*) AS numsongs,album_art._data AS album_art FROM audio LEFT OUTER JOIN album_art ON audio.album_id=album_art.album_id WHERE is_music=1 GROUP BY audio.album_id");
        sQLiteDatabase.execSQL("CREATE VIEW searchhelpertitle AS SELECT * FROM audio ORDER BY title_key");
        sQLiteDatabase.execSQL("CREATE VIEW artist_info AS SELECT artist_id AS _id, artist, artist_key, COUNT(DISTINCT album_key) AS number_of_albums, COUNT(*) AS number_of_tracks FROM audio WHERE is_music=1 GROUP BY artist_key");
        sQLiteDatabase.execSQL("CREATE VIEW search AS SELECT _id,'artist' AS mime_type,artist,NULL AS album,NULL AS title,artist AS text1,NULL AS text2,number_of_albums AS data1,number_of_tracks AS data2,artist_key AS match,'content://media/external/audio/artists/'||_id AS suggest_intent_data,1 AS grouporder FROM artist_info WHERE (artist!='<unknown>') UNION ALL SELECT _id,'album' AS mime_type,artist,album,NULL AS title,album AS text1,artist AS text2,NULL AS data1,NULL AS data2,artist_key||' '||album_key AS match,'content://media/external/audio/albums/'||_id AS suggest_intent_data,2 AS grouporder FROM album_info WHERE (album!='<unknown>') UNION ALL SELECT searchhelpertitle._id AS _id,mime_type,artist,album,title,title AS text1,artist AS text2,NULL AS data1,NULL AS data2,artist_key||' '||album_key||' '||title_key AS match,'content://media/external/audio/media/'||searchhelpertitle._id AS suggest_intent_data,3 AS grouporder FROM searchhelpertitle WHERE (title != '')");
        sQLiteDatabase.execSQL("CREATE VIEW audio_genres_map_noid AS SELECT audio_id,genre_id FROM audio_genres_map");
        sQLiteDatabase.execSQL("CREATE VIEW video AS SELECT " + String.join(",", getProjectionMap(MediaStore.Video.Media.class).keySet()) + " FROM files WHERE media_type=3");
        sQLiteDatabase.execSQL("CREATE VIEW images AS SELECT " + String.join(",", getProjectionMap(MediaStore.Images.Media.class).keySet()) + " FROM files WHERE media_type=1");
        sQLiteDatabase.execSQL("CREATE VIEW downloads AS SELECT " + String.join(",", getProjectionMap(MediaStore.Downloads.class).keySet()) + " FROM files WHERE is_download=1");
        MediaProviderUtils.updateOnePlusDebugTable(sQLiteDatabase);
    }

    private static void updateCollationKeys(SQLiteDatabase sQLiteDatabase) {
        sQLiteDatabase.execSQL("DELETE from albums");
        sQLiteDatabase.execSQL("DELETE from artists");
        sQLiteDatabase.execSQL("UPDATE files SET date_modified=0;");
    }

    private static void updateAddTitleResource(SQLiteDatabase sQLiteDatabase) {
        if (!MediaProviderUtils.isColumnExistedInDb(sQLiteDatabase, "files", "title_resource_uri")) {
            sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN title_resource_uri TEXT");
            sQLiteDatabase.execSQL("UPDATE files SET date_modified=0 WHERE (is_alarm IS 1) OR (is_ringtone IS 1) OR (is_notification IS 1)");
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:13:0x00a6, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:14:0x00a7, code lost:
        if (r11 != null) goto L_0x00a9;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:15:0x00a9, code lost:
        $closeResource(r10, r11);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:16:0x00ac, code lost:
        throw r0;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private static void updateAddOwnerPackageName(android.database.sqlite.SQLiteDatabase r10, boolean r11) {
        /*
        // Method dump skipped, instructions count: 173
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.updateAddOwnerPackageName(android.database.sqlite.SQLiteDatabase, boolean):void");
    }

    private static void updateAddColorSpaces(SQLiteDatabase sQLiteDatabase) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN color_standard INTEGER;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN color_transfer INTEGER;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN color_range INTEGER;");
    }

    private static void updateAddHashAndPending(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN _hash BLOB DEFAULT NULL;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN is_pending INTEGER DEFAULT 0;");
    }

    private static void updateAddDownloadInfo(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN is_download INTEGER DEFAULT 0;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN download_uri TEXT DEFAULT NULL;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN referer_uri TEXT DEFAULT NULL;");
    }

    private static void updateAddAudiobook(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN is_audiobook INTEGER DEFAULT 0;");
    }

    private static void updateClearLocation(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("UPDATE files SET latitude=NULL, longitude=NULL;");
    }

    private static void updateSetIsDownload(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("UPDATE files SET is_download=1 WHERE _data REGEXP '" + MediaStore.Downloads.PATTERN_DOWNLOADS_FILE + "'");
    }

    private static void updateAddExpiresAndTrashed(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN date_expires INTEGER DEFAULT NULL;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN is_trashed INTEGER DEFAULT 0;");
    }

    private static void updateAddGroupId(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN group_id INTEGER DEFAULT NULL;");
    }

    private static void updateAddDirectories(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN primary_directory TEXT DEFAULT NULL;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN secondary_directory TEXT DEFAULT NULL;");
    }

    private static void updateAddXmp(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN document_id TEXT DEFAULT NULL;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN instance_id TEXT DEFAULT NULL;");
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN original_document_id TEXT DEFAULT NULL;");
    }

    private static void updateAddPath(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN relative_path TEXT DEFAULT NULL;");
    }

    private static void updateAddVolumeName(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("ALTER TABLE files ADD COLUMN volume_name TEXT DEFAULT NULL;");
    }

    private static void updateDirsMimeType(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("UPDATE files SET mime_type=NULL WHERE format=12289");
    }

    private static void updateRelativePath(SQLiteDatabase sQLiteDatabase, boolean z) {
        sQLiteDatabase.execSQL("UPDATE files SET relative_path=relative_path||'/' WHERE relative_path IS NOT NULL AND relative_path NOT LIKE '%/';");
    }

    /* JADX DEBUG: Multi-variable search result rejected for r14v0, resolved type: boolean */
    /* JADX WARN: Multi-variable type inference failed */
    private static void recomputeDataValues(SQLiteDatabase sQLiteDatabase, boolean z) {
        Throwable th;
        Throwable th2;
        if (DBG) {
            Log.d(TAG, "recomputeDataValues(" + ((int) z) + ")+");
        }
        boolean z2 = false;
        try {
            Cursor query = sQLiteDatabase.query("files", new String[]{"_id", "_data"}, null, null, null, null, null, null);
            try {
                Log.d(TAG, "Recomputing " + query.getCount() + " data values");
                ContentValues contentValues = new ContentValues();
                boolean z3 = false;
                while (query.moveToNext()) {
                    try {
                        contentValues.clear();
                        long j = query.getLong(0);
                        contentValues.put("_data", query.getString(1));
                        computeDataValues(contentValues);
                        contentValues.remove("_data");
                        if (!contentValues.isEmpty()) {
                            if (!sQLiteDatabase.isOpen()) {
                                StringBuilder sb = new StringBuilder();
                                sb.append("recomputeDataValues(");
                                sb.append(z != 0 ? 1 : 0);
                                sb.append("): reopen db ");
                                sb.append(sQLiteDatabase.getPath());
                                Log.i(TAG, sb.toString());
                                sQLiteDatabase = SQLiteDatabase.openDatabase(sQLiteDatabase.getPath(), null, 0);
                                z3 = true;
                            }
                            sQLiteDatabase.update("files", contentValues, "_id=" + j, null);
                        }
                    } catch (Throwable th3) {
                        th2 = th3;
                        try {
                            throw th2;
                        } catch (Throwable th4) {
                            if (query != null) {
                                $closeResource(th2, query);
                            }
                            throw th4;
                        }
                    }
                }
                if (query != null) {
                    try {
                        $closeResource(null, query);
                    } catch (Throwable th5) {
                        th = th5;
                        z2 = z3;
                    }
                }
                if (z3 && sQLiteDatabase.isOpen()) {
                    sQLiteDatabase.close();
                }
                if (DBG) {
                    Log.d(TAG, "recomputeDataValues(" + (z ? 1 : 0) + ")-");
                }
            } catch (Throwable th6) {
                th2 = th6;
                throw th2;
            }
        } catch (Throwable th7) {
            th = th7;
            if (z2 && sQLiteDatabase.isOpen()) {
                sQLiteDatabase.close();
            }
            throw th;
        }
    }

    /* access modifiers changed from: private */
    /* JADX WARNING: Removed duplicated region for block: B:37:0x00e2  */
    /* JADX WARNING: Removed duplicated region for block: B:49:0x010f  */
    /* JADX WARNING: Removed duplicated region for block: B:55:0x0130  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public static void recomputeDataValuesByPage(android.database.sqlite.SQLiteDatabase r21, boolean r22) {
        /*
        // Method dump skipped, instructions count: 308
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.recomputeDataValuesByPage(android.database.sqlite.SQLiteDatabase, boolean):void");
    }

    /* access modifiers changed from: private */
    public static void updateDatabase(Context context, final SQLiteDatabase sQLiteDatabase, final boolean z, int i, int i2) {
        long elapsedRealtime = SystemClock.elapsedRealtime();
        MediaProviderUtils.updateOnePlusDebugTable(sQLiteDatabase);
        if (i < 700) {
            createLatestSchema(sQLiteDatabase, z);
        } else {
            boolean z2 = false;
            if (i < 800) {
                updateCollationKeys(sQLiteDatabase);
            }
            if (i <= VERSION_P) {
                updateAddTitleResource(sQLiteDatabase);
            }
            if (i < 1000) {
                updateAddOwnerPackageName(sQLiteDatabase, z);
            }
            if (i < 1003) {
                updateAddColorSpaces(sQLiteDatabase);
            }
            if (i < 1004) {
                updateAddHashAndPending(sQLiteDatabase, z);
            }
            if (i < 1005) {
                updateAddDownloadInfo(sQLiteDatabase, z);
            }
            if (i < 1006) {
                updateAddAudiobook(sQLiteDatabase, z);
            }
            if (i < 1007) {
                updateClearLocation(sQLiteDatabase, z);
            }
            if (i < 1008) {
                updateSetIsDownload(sQLiteDatabase, z);
            }
            if (i < 1010) {
                updateAddExpiresAndTrashed(sQLiteDatabase, z);
            }
            if (i < 1012) {
                z2 = true;
            }
            if (i < 1013) {
                updateAddGroupId(sQLiteDatabase, z);
                updateAddDirectories(sQLiteDatabase, z);
                z2 = true;
            }
            if (i < 1014) {
                updateAddXmp(sQLiteDatabase, z);
            }
            if (i < 1017) {
                updateSetIsDownload(sQLiteDatabase, z);
                z2 = true;
            }
            if (i < 1018) {
                updateAddPath(sQLiteDatabase, z);
                z2 = true;
            }
            if (i < 1019 && !z) {
                deleteLegacyThumbnailData();
            }
            if (i < 1020) {
                updateAddVolumeName(sQLiteDatabase, z);
                z2 = true;
            }
            if (i < 1022) {
                updateDirsMimeType(sQLiteDatabase, z);
            }
            if (i < VERSION_Q) {
                updateRelativePath(sQLiteDatabase, z);
            }
            if (z2) {
                new Thread(new Runnable() {
                    /* class com.android.providers.media.MediaProvider.AnonymousClass4 */

                    public void run() {
                        MediaProvider.recomputeDataValuesByPage(sQLiteDatabase, z);
                    }
                }).start();
            }
        }
        createLatestViews(sQLiteDatabase, z);
        sanityCheck(sQLiteDatabase, i);
        getOrCreateUuid(sQLiteDatabase);
        logToDb(sQLiteDatabase, "Database upgraded from version " + i + " to " + i2 + " in " + ((SystemClock.elapsedRealtime() - elapsedRealtime) / 1000) + " seconds");
    }

    /* access modifiers changed from: private */
    public static void downgradeDatabase(Context context, SQLiteDatabase sQLiteDatabase, boolean z, int i, int i2) {
        long elapsedRealtime = SystemClock.elapsedRealtime();
        createLatestSchema(sQLiteDatabase, z);
        logToDb(sQLiteDatabase, "Database downgraded from version " + i + " to " + i2 + " in " + ((SystemClock.elapsedRealtime() - elapsedRealtime) / 1000) + " seconds");
    }

    static void logToDb(SQLiteDatabase sQLiteDatabase, String str) {
        sQLiteDatabase.execSQL("INSERT OR REPLACE INTO log (time,message) VALUES (strftime('%Y-%m-%d %H:%M:%f','now'),?);", new String[]{str});
        sQLiteDatabase.execSQL("DELETE FROM log WHERE rowid IN (SELECT rowid FROM log ORDER BY rowid DESC LIMIT 500,-1);");
    }

    private static void sanityCheck(SQLiteDatabase sQLiteDatabase, int i) {
        Throwable th;
        Cursor cursor;
        try {
            cursor = sQLiteDatabase.query("audio_meta", new String[]{"count(*)"}, null, null, null, null, null);
            try {
                Cursor query = sQLiteDatabase.query("audio_meta", new String[]{"count(distinct _data)"}, null, null, null, null, null);
                cursor.moveToFirst();
                query.moveToFirst();
                int i2 = cursor.getInt(0);
                int i3 = query.getInt(0);
                if (i2 != i3) {
                    Log.e(TAG, "audio_meta._data column is not unique while upgrading from schema " + i + " : " + i2 + "/" + i3);
                    sQLiteDatabase.execSQL("DELETE FROM audio_meta;");
                }
                IoUtils.closeQuietly(cursor);
                IoUtils.closeQuietly(query);
            } catch (Throwable th2) {
                th = th2;
                IoUtils.closeQuietly(cursor);
                IoUtils.closeQuietly((AutoCloseable) null);
                throw th;
            }
        } catch (Throwable th3) {
            th = th3;
            cursor = null;
            IoUtils.closeQuietly(cursor);
            IoUtils.closeQuietly((AutoCloseable) null);
            throw th;
        }
    }

    private static String getOrCreateUuid(SQLiteDatabase sQLiteDatabase) {
        try {
            return new String(Os.getxattr(sQLiteDatabase.getPath(), XATTR_UUID));
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENODATA) {
                String uuid = UUID.randomUUID().toString();
                try {
                    Os.setxattr(sQLiteDatabase.getPath(), XATTR_UUID, uuid.getBytes(), 0);
                    return uuid;
                } catch (ErrnoException unused) {
                    throw new RuntimeException(e);
                }
            } else {
                throw new RuntimeException(e);
            }
        }
    }

    @VisibleForTesting
    static void computeDataValues(ContentValues contentValues) {
        contentValues.remove("bucket_id");
        contentValues.remove("bucket_display_name");
        contentValues.remove("group_id");
        contentValues.remove("volume_name");
        contentValues.remove("relative_path");
        contentValues.remove("primary_directory");
        contentValues.remove("secondary_directory");
        String asString = contentValues.getAsString("_data");
        if (!TextUtils.isEmpty(asString)) {
            File file = new File(asString);
            File file2 = new File(asString.toLowerCase());
            contentValues.put("volume_name", extractVolumeName(asString));
            contentValues.put("relative_path", extractRelativePath(asString));
            contentValues.put("_display_name", extractDisplayName(asString));
            String parent = file2.getParent();
            if (parent != null) {
                contentValues.put("bucket_id", Integer.valueOf(parent.hashCode()));
                if (!"/".equals(contentValues.getAsString("relative_path"))) {
                    contentValues.put("bucket_display_name", file.getParentFile().getName());
                }
            }
            String name = file2.getName();
            int indexOf = name.indexOf(46);
            if (indexOf > 0) {
                contentValues.put("group_id", Integer.valueOf(name.substring(0, indexOf).hashCode()));
            }
            String asString2 = contentValues.getAsString("relative_path");
            if (!TextUtils.isEmpty(asString2)) {
                String[] split = asString2.split("/");
                if (split.length > 0) {
                    contentValues.put("primary_directory", split[0]);
                }
                if (split.length > 1) {
                    contentValues.put("secondary_directory", split[1]);
                }
            }
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:11:0x002d, code lost:
        if (r1 != com.android.providers.media.MediaProvider.VIDEO_MEDIA_ID) goto L_0x0074;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:30:0x007b, code lost:
        r12 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:31:0x007c, code lost:
        if (r5 != null) goto L_0x007e;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:32:0x007e, code lost:
        $closeResource(r11, r5);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:33:0x0081, code lost:
        throw r12;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public android.net.Uri canonicalize(android.net.Uri r12) {
        /*
        // Method dump skipped, instructions count: 141
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.canonicalize(android.net.Uri):android.net.Uri");
    }

    /* JADX WARNING: Code restructure failed: missing block: B:27:0x0088, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:29:0x008a, code lost:
        if (r1 != null) goto L_0x008c;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:30:0x008c, code lost:
        $closeResource(r0, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:31:0x008f, code lost:
        throw r0;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:47:0x00d5, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:49:0x00d7, code lost:
        if (r1 != null) goto L_0x00d9;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:50:0x00d9, code lost:
        $closeResource(r0, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:51:0x00dc, code lost:
        throw r0;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:71:0x0123, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:73:0x0125, code lost:
        if (r1 != null) goto L_0x0127;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:74:0x0127, code lost:
        $closeResource(r0, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:75:0x012a, code lost:
        throw r0;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:90:0x016f, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:92:0x0171, code lost:
        if (r1 != null) goto L_0x0173;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:93:0x0173, code lost:
        $closeResource(r0, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:94:0x0176, code lost:
        throw r0;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public android.net.Uri uncanonicalize(android.net.Uri r21) {
        /*
        // Method dump skipped, instructions count: 401
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.uncanonicalize(android.net.Uri):android.net.Uri");
    }

    private Uri safeUncanonicalize(Uri uri) {
        Uri uncanonicalize = uncanonicalize(uri);
        return uncanonicalize != null ? uncanonicalize : uri;
    }

    public Cursor query(Uri uri, String[] strArr, String str, String[] strArr2, String str2) {
        return query(uri, strArr, ContentResolver.createSqlQueryBundle(str, strArr2, str2), null);
    }

    public Cursor query(Uri uri, String[] strArr, Bundle bundle, CancellationSignal cancellationSignal) {
        Trace.traceBegin(1048576, "query");
        try {
            return queryInternal(uri, strArr, bundle, cancellationSignal);
        } finally {
            Trace.traceEnd(1048576);
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:64:0x017e, code lost:
        if (r15.size() > 0) goto L_0x0192;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:71:0x0190, code lost:
        if (r15.size() <= 0) goto L_0x01bd;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:72:0x0192, code lost:
        r0 = (java.lang.String[]) r15.toArray(new java.lang.String[r15.size()]);
     */
    /* JADX WARNING: Removed duplicated region for block: B:125:0x02d7  */
    /* JADX WARNING: Removed duplicated region for block: B:128:0x02ed  */
    /* JADX WARNING: Removed duplicated region for block: B:38:0x0119  */
    /* JADX WARNING: Removed duplicated region for block: B:85:0x01ca  */
    /* JADX WARNING: Removed duplicated region for block: B:92:0x020f  */
    /* JADX WARNING: Removed duplicated region for block: B:93:0x0212  */
    /* JADX WARNING: Removed duplicated region for block: B:96:0x021b  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private android.database.Cursor queryInternal(android.net.Uri r20, java.lang.String[] r21, android.os.Bundle r22, android.os.CancellationSignal r23) {
        /*
        // Method dump skipped, instructions count: 804
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.queryInternal(android.net.Uri, java.lang.String[], android.os.Bundle, android.os.CancellationSignal):android.database.Cursor");
    }

    /* JADX INFO: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARNING: Code restructure failed: missing block: B:45:0x0089, code lost:
        r2 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:46:0x008a, code lost:
        if (r9 != null) goto L_0x008c;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:47:0x008c, code lost:
        $closeResource(r1, r9);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:48:0x008f, code lost:
        throw r2;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public java.lang.String getType(android.net.Uri r9) {
        /*
        // Method dump skipped, instructions count: 198
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.getType(android.net.Uri):java.lang.String");
    }

    @VisibleForTesting
    static void ensureFileColumns(Uri uri, ContentValues contentValues) throws VolumeArgumentException {
        ensureNonUniqueFileColumns(matchUri(uri, true), uri, contentValues, null);
    }

    private static void ensureUniqueFileColumns(int i, Uri uri, ContentValues contentValues) throws VolumeArgumentException {
        ensureFileColumns(i, uri, contentValues, true, null);
    }

    private static void ensureNonUniqueFileColumns(int i, Uri uri, ContentValues contentValues, String str) throws VolumeArgumentException {
        ensureFileColumns(i, uri, contentValues, false, str);
    }

    /* JADX WARNING: Removed duplicated region for block: B:121:0x02fb  */
    /* JADX WARNING: Removed duplicated region for block: B:54:0x0134  */
    /* JADX WARNING: Removed duplicated region for block: B:62:0x0164  */
    /* JADX WARNING: Removed duplicated region for block: B:65:0x0177  */
    /* JADX WARNING: Removed duplicated region for block: B:66:0x0179  */
    /* JADX WARNING: Removed duplicated region for block: B:69:0x0181  */
    /* JADX WARNING: Removed duplicated region for block: B:70:0x0185  */
    /* JADX WARNING: Removed duplicated region for block: B:79:0x01ad  */
    /* JADX WARNING: Removed duplicated region for block: B:83:0x01e5  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private static void ensureFileColumns(int r19, android.net.Uri r20, android.content.ContentValues r21, boolean r22, java.lang.String r23) throws com.android.providers.media.MediaProvider.VolumeArgumentException {
        /*
        // Method dump skipped, instructions count: 795
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.ensureFileColumns(int, android.net.Uri, android.content.ContentValues, boolean, java.lang.String):void");
    }

    private static String[] sanitizePath(String str) {
        if (str == null) {
            return EmptyArray.STRING;
        }
        String[] split = str.split("/");
        for (int i = 0; i < split.length; i++) {
            split[i] = sanitizeDisplayName(split[i]);
        }
        return split;
    }

    private static String sanitizeDisplayName(String str) {
        if (str == null) {
            return null;
        }
        if (!str.startsWith(".")) {
            return FileUtils.buildValidFatFilename(str);
        }
        return FileUtils.buildValidFatFilename("_" + str);
    }

    private static void assertFileColumnsSane(int i, Uri uri, ContentValues contentValues) throws VolumeArgumentException {
        if (contentValues.containsKey("_data")) {
            try {
                Collection<File> volumeScanPaths = getVolumeScanPaths(resolveVolumeName(uri));
                File canonicalFile = new File(contentValues.getAsString("_data")).getCanonicalFile();
                if (!FileUtils.contains(volumeScanPaths, canonicalFile)) {
                    throw new VolumeArgumentException(canonicalFile, volumeScanPaths);
                }
            } catch (IOException e) {
                throw new IllegalArgumentException(e);
            }
        }
    }

    public int bulkInsert(Uri uri, ContentValues[] contentValuesArr) {
        int callingPackageTargetSdkVersion = getCallingPackageTargetSdkVersion();
        int matchUri = matchUri(uri, isCallingPackageAllowedHidden());
        if (matchUri == VOLUMES) {
            return super.bulkInsert(uri, contentValuesArr);
        }
        try {
            DatabaseHelper databaseForUri = getDatabaseForUri(uri);
            SQLiteDatabase writableDatabase = databaseForUri.getWritableDatabase();
            if (matchUri == MTP_OBJECT_REFERENCES) {
                return setObjectReferences(databaseForUri, writableDatabase, Integer.parseInt(uri.getPathSegments().get(2)), contentValuesArr);
            }
            databaseForUri.beginTransaction();
            try {
                int bulkInsert = super.bulkInsert(uri, contentValuesArr);
                databaseForUri.setTransactionSuccessful();
                return bulkInsert;
            } finally {
                databaseForUri.endTransaction();
            }
        } catch (VolumeNotFoundException e) {
            return e.translateForUpdateDelete(callingPackageTargetSdkVersion);
        }
    }

    /* JADX INFO: finally extract failed */
    private int playlistBulkInsert(SQLiteDatabase sQLiteDatabase, Uri uri, ContentValues[] contentValuesArr) {
        DatabaseUtils.InsertHelper insertHelper = new DatabaseUtils.InsertHelper(sQLiteDatabase, "audio_playlists_map");
        int columnIndex = insertHelper.getColumnIndex("audio_id");
        int columnIndex2 = insertHelper.getColumnIndex("playlist_id");
        int columnIndex3 = insertHelper.getColumnIndex("play_order");
        long parseLong = Long.parseLong(uri.getPathSegments().get(3));
        sQLiteDatabase.beginTransaction();
        try {
            int length = contentValuesArr.length;
            for (int i = 0; i < length; i++) {
                insertHelper.prepareForInsert();
                insertHelper.bind(columnIndex, ((Number) contentValuesArr[i].get("audio_id")).longValue());
                insertHelper.bind(columnIndex2, parseLong);
                insertHelper.bind(columnIndex3, ((Number) contentValuesArr[i].get("play_order")).intValue());
                insertHelper.execute();
            }
            sQLiteDatabase.setTransactionSuccessful();
            sQLiteDatabase.endTransaction();
            insertHelper.close();
            getContext().getContentResolver().notifyChange(uri, null);
            return length;
        } catch (Throwable th) {
            sQLiteDatabase.endTransaction();
            insertHelper.close();
            throw th;
        }
    }

    private long insertDirectory(DatabaseHelper databaseHelper, SQLiteDatabase sQLiteDatabase, String str) {
        if (LOCAL_LOGV) {
            Log.v(TAG, "inserting directory " + str);
        }
        ContentValues contentValues = new ContentValues();
        contentValues.put("format", (Integer) 12289);
        contentValues.put("_data", str);
        contentValues.put("parent", Long.valueOf(getParent(databaseHelper, sQLiteDatabase, str)));
        contentValues.put("owner_package_name", extractPathOwnerPackageName(str));
        contentValues.put("volume_name", extractVolumeName(str));
        contentValues.put("relative_path", extractRelativePath(str));
        contentValues.put("_display_name", extractDisplayName(str));
        contentValues.put("is_download", Boolean.valueOf(MediaStore.Downloads.isDownload(str)));
        File file = new File(str);
        if (file.exists()) {
            contentValues.put("date_modified", Long.valueOf(file.lastModified() / 1000));
        }
        return sQLiteDatabase.insert("files", "date_modified", contentValues);
    }

    private static String extractVolumeName(String str) {
        if (str == null) {
            return null;
        }
        Matcher matcher = PATTERN_VOLUME_NAME.matcher(str);
        if (!matcher.find()) {
            return "internal";
        }
        String group = matcher.group(1);
        if (group.equals("emulated")) {
            return "external_primary";
        }
        return StorageVolume.normalizeUuid(group);
    }

    private static String extractRelativePath(String str) {
        if (str == null) {
            return null;
        }
        Matcher matcher = PATTERN_RELATIVE_PATH.matcher(str);
        if (!matcher.find()) {
            return null;
        }
        int lastIndexOf = str.lastIndexOf(47);
        return (lastIndexOf == -1 || lastIndexOf < matcher.end()) ? "/" : str.substring(matcher.end(), lastIndexOf + 1);
    }

    private static String extractDisplayName(String str) {
        if (str == null) {
            return null;
        }
        if (str.endsWith("/")) {
            str = str.substring(0, str.length() - 1);
        }
        return str.substring(str.lastIndexOf(47) + 1);
    }

    /* JADX WARNING: Code restructure failed: missing block: B:12:0x0028, code lost:
        r1 = r11.query("files", new java.lang.String[]{"_id"}, "_data=?", new java.lang.String[]{r12}, null, null, null);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:15:0x0045, code lost:
        if (r1.moveToFirst() == false) goto L_0x004c;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:16:0x0047, code lost:
        r10 = r1.getLong(0);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:17:0x004c, code lost:
        r10 = insertDirectory(r10, r11, r12);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:18:0x0050, code lost:
        if (r1 == null) goto L_0x0055;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:19:0x0052, code lost:
        $closeResource(null, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:20:0x0055, code lost:
        r0 = r9.mDirectoryCache;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:21:0x0057, code lost:
        monitor-enter(r0);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:23:?, code lost:
        r9.mDirectoryCache.put(r12, java.lang.Long.valueOf(r10));
     */
    /* JADX WARNING: Code restructure failed: missing block: B:24:0x0061, code lost:
        monitor-exit(r0);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:25:0x0062, code lost:
        return r10;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:32:0x0068, code lost:
        r10 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:33:0x0069, code lost:
        if (r1 != null) goto L_0x006b;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:34:0x006b, code lost:
        $closeResource(r9, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:35:0x006e, code lost:
        throw r10;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private long getParent(com.android.providers.media.MediaProvider.DatabaseHelper r10, android.database.sqlite.SQLiteDatabase r11, java.lang.String r12) {
        /*
        // Method dump skipped, instructions count: 114
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.getParent(com.android.providers.media.MediaProvider$DatabaseHelper, android.database.sqlite.SQLiteDatabase, java.lang.String):long");
    }

    /* JADX WARNING: Removed duplicated region for block: B:11:? A[RETURN, SYNTHETIC] */
    /* JADX WARNING: Removed duplicated region for block: B:9:0x0017  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private java.lang.String getDefaultTitleFromCursor(android.database.Cursor r3) {
        /*
            r2 = this;
            java.lang.String r0 = "title_resource_uri"
            int r0 = r3.getColumnIndex(r0)
            r1 = -1
            if (r0 <= r1) goto L_0x0014
            java.lang.String r0 = r3.getString(r0)
            if (r0 == 0) goto L_0x0014
            java.lang.String r2 = r2.getDefaultTitle(r0)     // Catch:{ Exception -> 0x0014 }
            goto L_0x0015
        L_0x0014:
            r2 = 0
        L_0x0015:
            if (r2 != 0) goto L_0x0021
            java.lang.String r2 = "title"
            int r2 = r3.getColumnIndex(r2)
            java.lang.String r2 = r3.getString(r2)
        L_0x0021:
            return r2
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.getDefaultTitleFromCursor(android.database.Cursor):java.lang.String");
    }

    private String getDefaultTitle(String str) throws Exception {
        try {
            return getTitleFromResourceUri(str, false);
        } catch (Exception e) {
            Log.e(TAG, "Error getting default title for " + str, e);
            throw e;
        }
    }

    private String getLocalizedTitle(String str) throws Exception {
        try {
            return getTitleFromResourceUri(str, true);
        } catch (Exception e) {
            Log.e(TAG, "Error getting localized title for " + str, e);
            throw e;
        }
    }

    private String getTitleFromResourceUri(String str, boolean z) throws Exception {
        Resources resources;
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        Uri parse = Uri.parse(str);
        if (!"android.resource".equals(parse.getScheme())) {
            return null;
        }
        List<String> pathSegments = parse.getPathSegments();
        if (pathSegments.size() != 2) {
            Log.e(TAG, "Error getting localized title for " + str + ", must have 2 path segments");
            return null;
        }
        String str2 = pathSegments.get(0);
        if (!"string".equals(str2)) {
            Log.e(TAG, "Error getting localized title for " + str + ", first path segment must be \"string\"");
            return null;
        }
        String authority = parse.getAuthority();
        if (z) {
            resources = this.mPackageManager.getResourcesForApplication(authority);
        } else {
            Context createPackageContext = getContext().createPackageContext(authority, 0);
            Configuration configuration = createPackageContext.getResources().getConfiguration();
            configuration.setLocale(Locale.US);
            resources = createPackageContext.createConfigurationContext(configuration).getResources();
        }
        return resources.getString(resources.getIdentifier(pathSegments.get(1), str2, authority));
    }

    public void onLocaleChanged() {
        localizeTitles();
    }

    /* JADX WARNING: Code restructure failed: missing block: B:14:0x0077, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:15:0x0078, code lost:
        if (r1 != null) goto L_0x007a;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:16:0x007a, code lost:
        $closeResource(r10, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:17:0x007d, code lost:
        throw r0;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private void localizeTitles() {
        /*
        // Method dump skipped, instructions count: 126
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.localizeTitles():void");
    }

    /* JADX WARNING: Removed duplicated region for block: B:180:0x0401  */
    /* JADX WARNING: Removed duplicated region for block: B:190:0x0449  */
    /* JADX WARNING: Removed duplicated region for block: B:218:0x04cb  */
    /* JADX WARNING: Removed duplicated region for block: B:220:0x04df  */
    /* JADX WARNING: Removed duplicated region for block: B:57:0x017b A[Catch:{ all -> 0x013d }] */
    /* JADX WARNING: Removed duplicated region for block: B:61:0x01a1 A[Catch:{ all -> 0x01ff }] */
    /* JADX WARNING: Removed duplicated region for block: B:65:0x01c9  */
    /* JADX WARNING: Removed duplicated region for block: B:66:0x01cc  */
    /* JADX WARNING: Removed duplicated region for block: B:71:0x01d8 A[Catch:{ Exception -> 0x01e5 }] */
    /* JADX WARNING: Removed duplicated region for block: B:72:0x01df A[Catch:{ Exception -> 0x01e5 }] */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private long insertFile(com.android.providers.media.MediaProvider.DatabaseHelper r31, int r32, android.net.Uri r33, android.content.ContentValues r34, int r35, boolean r36) {
        /*
        // Method dump skipped, instructions count: 1296
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.insertFile(com.android.providers.media.MediaProvider$DatabaseHelper, int, android.net.Uri, android.content.ContentValues, int, boolean):long");
    }

    private Cursor getObjectReferences(DatabaseHelper databaseHelper, SQLiteDatabase sQLiteDatabase, int i) {
        Cursor query = sQLiteDatabase.query("files", sMediaTableColumns, "_id=?", new String[]{Integer.toString(i)}, null, null, null);
        if (query != null) {
            try {
                if (query.moveToNext()) {
                    long j = query.getLong(0);
                    if (query.getInt(1) != 4) {
                        return null;
                    }
                    Cursor rawQuery = sQLiteDatabase.rawQuery(OBJECT_REFERENCES_QUERY, new String[]{Long.toString(j)});
                    IoUtils.closeQuietly(query);
                    return rawQuery;
                }
            } finally {
                IoUtils.closeQuietly(query);
            }
        }
        IoUtils.closeQuietly(query);
        return null;
    }

    /* JADX INFO: finally extract failed */
    /* JADX WARNING: Removed duplicated region for block: B:17:0x0047 A[RETURN] */
    /* JADX WARNING: Removed duplicated region for block: B:18:0x0048  */
    /* JADX WARNING: Removed duplicated region for block: B:36:0x00be  */
    /* JADX WARNING: Removed duplicated region for block: B:37:0x00e6  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private int setObjectReferences(com.android.providers.media.MediaProvider.DatabaseHelper r22, android.database.sqlite.SQLiteDatabase r23, int r24, android.content.ContentValues[] r25) {
        /*
        // Method dump skipped, instructions count: 272
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.setObjectReferences(com.android.providers.media.MediaProvider$DatabaseHelper, android.database.sqlite.SQLiteDatabase, int, android.content.ContentValues[]):int");
    }

    /* JADX WARNING: Removed duplicated region for block: B:10:0x003b A[Catch:{ all -> 0x0058 }] */
    /* JADX WARNING: Removed duplicated region for block: B:13:0x0046  */
    /* JADX WARNING: Removed duplicated region for block: B:18:? A[RETURN, SYNTHETIC] */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private void updateGenre(long r9, java.lang.String r11, java.lang.String r12) {
        /*
            r8 = this;
            android.net.Uri r12 = android.provider.MediaStore.Audio.Genres.getContentUri(r12)
            r6 = 0
            java.lang.String[] r2 = com.android.providers.media.MediaProvider.GENRE_LOOKUP_PROJECTION     // Catch:{ all -> 0x0058 }
            java.lang.String r3 = "name=?"
            r0 = 1
            java.lang.String[] r4 = new java.lang.String[r0]     // Catch:{ all -> 0x0058 }
            r7 = 0
            r4[r7] = r11     // Catch:{ all -> 0x0058 }
            r5 = 0
            r0 = r8
            r1 = r12
            android.database.Cursor r6 = r0.query(r1, r2, r3, r4, r5)     // Catch:{ all -> 0x0058 }
            if (r6 == 0) goto L_0x002b
            int r0 = r6.getCount()     // Catch:{ all -> 0x0058 }
            if (r0 != 0) goto L_0x001f
            goto L_0x002b
        L_0x001f:
            r6.moveToNext()     // Catch:{ all -> 0x0058 }
            long r0 = r6.getLong(r7)     // Catch:{ all -> 0x0058 }
            android.net.Uri r11 = android.content.ContentUris.withAppendedId(r12, r0)     // Catch:{ all -> 0x0058 }
            goto L_0x0039
        L_0x002b:
            android.content.ContentValues r0 = new android.content.ContentValues     // Catch:{ all -> 0x0058 }
            r0.<init>()     // Catch:{ all -> 0x0058 }
            java.lang.String r1 = "name"
            r0.put(r1, r11)     // Catch:{ all -> 0x0058 }
            android.net.Uri r11 = r8.insert(r12, r0)     // Catch:{ all -> 0x0058 }
        L_0x0039:
            if (r11 == 0) goto L_0x0041
            java.lang.String r12 = "members"
            android.net.Uri r11 = android.net.Uri.withAppendedPath(r11, r12)     // Catch:{ all -> 0x0058 }
        L_0x0041:
            libcore.io.IoUtils.closeQuietly(r6)
            if (r11 == 0) goto L_0x0057
            android.content.ContentValues r12 = new android.content.ContentValues
            r12.<init>()
            java.lang.Long r9 = java.lang.Long.valueOf(r9)
            java.lang.String r10 = "audio_id"
            r12.put(r10, r9)
            r8.insert(r11, r12)
        L_0x0057:
            return
        L_0x0058:
            r8 = move-exception
            libcore.io.IoUtils.closeQuietly(r6)
            throw r8
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.updateGenre(long, java.lang.String, java.lang.String):void");
    }

    @VisibleForTesting
    static String extractPathOwnerPackageName(String str) {
        if (str == null) {
            return null;
        }
        Matcher matcher = PATTERN_OWNED_PATH.matcher(str);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return null;
    }

    private void maybePut(ContentValues contentValues, String str, String str2) {
        if (str2 != null) {
            contentValues.put(str, str2);
        }
    }

    private boolean maybeMarkAsDownload(ContentValues contentValues) {
        String asString = contentValues.getAsString("_data");
        if (asString == null || !MediaStore.Downloads.isDownload(asString)) {
            return false;
        }
        contentValues.put("is_download", (Boolean) true);
        return true;
    }

    /* access modifiers changed from: private */
    public static String resolveVolumeName(Uri uri) {
        String volumeName = MediaStore.getVolumeName(uri);
        return "external".equals(volumeName) ? "external_primary" : volumeName;
    }

    public Uri insert(Uri uri, ContentValues contentValues) {
        Trace.traceBegin(1048576, "insert");
        try {
            return insertInternal(uri, contentValues);
        } finally {
            Trace.traceEnd(1048576);
        }
    }

    /* JADX WARNING: Removed duplicated region for block: B:69:0x017b A[Catch:{ all -> 0x0199 }] */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private android.net.Uri insertInternal(android.net.Uri r26, android.content.ContentValues r27) {
        /*
        // Method dump skipped, instructions count: 1512
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.insertInternal(android.net.Uri, android.content.ContentValues):android.net.Uri");
    }

    /*  JADX ERROR: StackOverflowError in pass: MarkFinallyVisitor
        java.lang.StackOverflowError
        	at jadx.core.dex.nodes.InsnNode.isSame(InsnNode.java:303)
        	at jadx.core.dex.instructions.InvokeNode.isSame(InvokeNode.java:77)
        	at jadx.core.dex.visitors.MarkFinallyVisitor.sameInsns(MarkFinallyVisitor.java:451)
        	at jadx.core.dex.visitors.MarkFinallyVisitor.compareBlocks(MarkFinallyVisitor.java:436)
        	at jadx.core.dex.visitors.MarkFinallyVisitor.checkBlocksTree(MarkFinallyVisitor.java:408)
        	at jadx.core.dex.visitors.MarkFinallyVisitor.checkBlocksTree(MarkFinallyVisitor.java:411)
        */
    @Override // android.content.ContentProvider
    public android.content.ContentProviderResult[] applyBatch(java.util.ArrayList<android.content.ContentProviderOperation> r6) throws android.content.OperationApplicationException {
        /*
        // Method dump skipped, instructions count: 140
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.applyBatch(java.util.ArrayList):android.content.ContentProviderResult[]");
    }

    private static void appendWhereStandalone(SQLiteQueryBuilder sQLiteQueryBuilder, String str, Object... objArr) {
        sQLiteQueryBuilder.appendWhereStandalone(DatabaseUtils.bindSelection(str, objArr));
    }

    static String bindList(Object... objArr) {
        StringBuilder sb = new StringBuilder();
        sb.append('(');
        for (int i = 0; i < objArr.length; i++) {
            sb.append('?');
            if (i < objArr.length - 1) {
                sb.append(',');
            }
        }
        sb.append(')');
        return DatabaseUtils.bindSelection(sb.toString(), objArr);
    }

    private static boolean parseBoolean(String str) {
        if (str == null) {
            return false;
        }
        return "1".equals(str) || "true".equalsIgnoreCase(str);
    }

    @Deprecated
    private String getSharedPackages(String str) {
        return bindList(this.mCallingIdentity.get().getSharedPackageNames());
    }

    private SQLiteQueryBuilder getQueryBuilder(int i, Uri uri, int i2, Bundle bundle) {
        Trace.traceBegin(1048576, "getQueryBuilder");
        try {
            return getQueryBuilderInternal(i, uri, i2, bundle);
        } finally {
            Trace.traceEnd(1048576);
        }
    }

    /* JADX DEBUG: Multi-variable search result rejected for r12v26, resolved type: int */
    /* JADX DEBUG: Multi-variable search result rejected for r12v29, resolved type: int */
    /* JADX DEBUG: Multi-variable search result rejected for r3v92, resolved type: int */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r12v27 */
    /* JADX WARN: Type inference failed for: r12v28 */
    /* JADX WARN: Type inference failed for: r12v30 */
    /* JADX WARN: Type inference failed for: r12v31 */
    /* JADX WARN: Type inference failed for: r3v93 */
    /* JADX WARN: Type inference failed for: r3v97 */
    /* JADX WARNING: Removed duplicated region for block: B:104:0x0303  */
    /* JADX WARNING: Removed duplicated region for block: B:106:0x0310  */
    /* JADX WARNING: Removed duplicated region for block: B:110:0x0337  */
    /* JADX WARNING: Removed duplicated region for block: B:111:0x034d  */
    /* JADX WARNING: Removed duplicated region for block: B:137:0x0432  */
    /* JADX WARNING: Removed duplicated region for block: B:138:0x0441  */
    /* JADX WARNING: Removed duplicated region for block: B:146:0x047f  */
    /* JADX WARNING: Removed duplicated region for block: B:148:0x048c  */
    /* JADX WARNING: Removed duplicated region for block: B:236:0x07d7  */
    /* JADX WARNING: Removed duplicated region for block: B:68:0x01d9  */
    /* JADX WARNING: Removed duplicated region for block: B:73:0x0208  */
    /* JADX WARNING: Removed duplicated region for block: B:77:0x0224  */
    /* JADX WARNING: Removed duplicated region for block: B:82:0x0242  */
    /* JADX WARNING: Removed duplicated region for block: B:88:0x0275  */
    /* JADX WARNING: Removed duplicated region for block: B:89:0x0296  */
    /* JADX WARNING: Removed duplicated region for block: B:96:0x02c3  */
    /* JADX WARNING: Removed duplicated region for block: B:97:0x02d3  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private android.database.sqlite.SQLiteQueryBuilder getQueryBuilderInternal(int r23, android.net.Uri r24, int r25, android.os.Bundle r26) {
        /*
        // Method dump skipped, instructions count: 2094
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.getQueryBuilderInternal(int, android.net.Uri, int, android.os.Bundle):android.database.sqlite.SQLiteQueryBuilder");
    }

    private static boolean hasOwnerPackageName(Uri uri) {
        int matchUri = matchUri(uri, true);
        if (matchUri == 4 || matchUri == IMAGES_THUMBNAILS_ID || matchUri == VIDEO_THUMBNAILS || matchUri == VIDEO_THUMBNAILS_ID) {
            return false;
        }
        switch (matchUri) {
            case AUDIO_ALBUMART /*{ENCODED_INT: 119}*/:
            case AUDIO_ALBUMART_ID /*{ENCODED_INT: 120}*/:
            case AUDIO_ALBUMART_FILE_ID /*{ENCODED_INT: 121}*/:
                return false;
            default:
                return true;
        }
    }

    public int delete(Uri uri, String str, String[] strArr) {
        Trace.traceBegin(1048576, "insert");
        try {
            return deleteInternal(uri, str, strArr);
        } finally {
            Trace.traceEnd(1048576);
        }
    }

    /* JADX INFO: finally extract failed */
    /* JADX WARNING: Code restructure failed: missing block: B:187:0x04b6, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:189:0x04b8, code lost:
        if (r4 != null) goto L_0x04ba;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:190:0x04ba, code lost:
        $closeResource(r0, r4);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:191:0x04bd, code lost:
        throw r0;
     */
    /* JADX WARNING: Removed duplicated region for block: B:122:0x036e A[ADDED_TO_REGION] */
    /* JADX WARNING: Removed duplicated region for block: B:128:0x037d  */
    /* JADX WARNING: Removed duplicated region for block: B:173:0x045d  */
    /* JADX WARNING: Removed duplicated region for block: B:198:0x04c8  */
    /* JADX WARNING: Removed duplicated region for block: B:209:0x0384 A[SYNTHETIC] */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private int deleteInternal(android.net.Uri r33, java.lang.String r34, java.lang.String[] r35) {
        /*
        // Method dump skipped, instructions count: 1244
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.deleteInternal(android.net.Uri, java.lang.String, java.lang.String[]):int");
    }

    public /* synthetic */ void lambda$deleteInternal$4$MediaProvider(Uri uri) {
        getContext().revokeUriPermission(uri, 3);
    }

    private int deleteRecursive(SQLiteQueryBuilder sQLiteQueryBuilder, SQLiteDatabase sQLiteDatabase, String str, String[] strArr) {
        int delete;
        sQLiteDatabase.beginTransaction();
        int i = 0;
        do {
            try {
                delete = sQLiteQueryBuilder.delete(sQLiteDatabase, str, strArr);
                i += delete;
            } finally {
                sQLiteDatabase.endTransaction();
            }
        } while (delete > 0);
        sQLiteDatabase.setTransactionSuccessful();
        return i;
    }

    /* JADX INFO: Can't fix incorrect switch cases order, some code will duplicate */
    /* JADX WARNING: Code restructure failed: missing block: B:60:0x0110, code lost:
        r13 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:61:0x0111, code lost:
        if (r14 != null) goto L_0x0113;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:62:0x0113, code lost:
        $closeResource(r12, r14);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:63:0x0116, code lost:
        throw r13;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:81:0x0151, code lost:
        r14 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:82:0x0152, code lost:
        if (r12 != null) goto L_0x0154;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:83:0x0154, code lost:
        $closeResource(r13, r12);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:84:0x0157, code lost:
        throw r14;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public android.os.Bundle call(java.lang.String r13, java.lang.String r14, android.os.Bundle r15) {
        /*
        // Method dump skipped, instructions count: 608
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.call(java.lang.String, java.lang.String, android.os.Bundle):android.os.Bundle");
    }

    public /* synthetic */ void lambda$call$5$MediaProvider(Uri uri) {
        delete(uri, null, null);
    }

    private long forEachContributedMedia(String str, Consumer<Uri> consumer) {
        Throwable th;
        Throwable th2;
        MediaProvider mediaProvider = this;
        DatabaseHelper databaseHelper = mediaProvider.mExternalDatabase;
        SQLiteDatabase readableDatabase = databaseHelper.getReadableDatabase();
        SQLiteQueryBuilder sQLiteQueryBuilder = new SQLiteQueryBuilder();
        sQLiteQueryBuilder.setTables("files");
        StringBuilder sb = new StringBuilder();
        int i = 1;
        int i2 = 0;
        sb.append(DatabaseUtils.bindSelection("owner_package_name=?", new Object[]{str}));
        sb.append(" AND NOT ");
        sb.append("_data");
        sb.append(" REGEXP '");
        sb.append(PATTERN_OWNED_PATH.pattern());
        sb.append("'");
        sQLiteQueryBuilder.appendWhere(sb.toString());
        LocalCallingIdentity clearLocalCallingIdentity = clearLocalCallingIdentity();
        try {
            Cursor query = sQLiteQueryBuilder.query(readableDatabase, new String[]{"volume_name", "_id", "_size", "_data"}, null, null, null, null, null, null);
            long j = 0;
            while (query.moveToNext()) {
                try {
                    try {
                        String string = query.getString(i2);
                        long j2 = query.getLong(i);
                        long j3 = query.getLong(2);
                        String string2 = query.getString(3);
                        Log.d(TAG, "Found " + string2 + " from " + str + " in " + databaseHelper.mName + " with size " + j3);
                        if (consumer != null) {
                            consumer.accept(MediaStore.Files.getContentUri(string, j2));
                        }
                        j += j3;
                        i = 1;
                        i2 = 0;
                    } catch (Throwable th3) {
                        th2 = th3;
                        try {
                            throw th2;
                        } catch (Throwable th4) {
                            if (query != null) {
                                $closeResource(th2, query);
                            }
                            throw th4;
                        }
                    }
                } catch (Throwable th5) {
                    th2 = th5;
                    throw th2;
                }
            }
            if (query != null) {
                try {
                    $closeResource(null, query);
                } catch (Throwable th6) {
                    th = th6;
                    mediaProvider = this;
                }
            }
            restoreLocalCallingIdentity(clearLocalCallingIdentity);
            return j;
        } catch (Throwable th7) {
            th = th7;
            mediaProvider.restoreLocalCallingIdentity(clearLocalCallingIdentity);
            throw th;
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:32:0x00ea, code lost:
        r14 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:33:0x00eb, code lost:
        if (r1 != null) goto L_0x00ed;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:34:0x00ed, code lost:
        $closeResource(r13, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:35:0x00f0, code lost:
        throw r14;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private void pruneThumbnails(android.os.CancellationSignal r14) {
        /*
        // Method dump skipped, instructions count: 241
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.pruneThumbnails(android.os.CancellationSignal):void");
    }

    /* access modifiers changed from: package-private */
    public static abstract class Thumbnailer {
        final String directoryName;

        public abstract Bitmap getThumbnailBitmap(Uri uri, CancellationSignal cancellationSignal) throws IOException;

        public Thumbnailer(String str) {
            this.directoryName = str;
        }

        private File getThumbnailFile(Uri uri) throws IOException {
            File volumePath = MediaProvider.getVolumePath(MediaProvider.resolveVolumeName(uri));
            return Environment.buildPath(volumePath, new String[]{this.directoryName, ".thumbnails", ContentUris.parseId(uri) + ".jpg"});
        }

        /* JADX WARNING: Code restructure failed: missing block: B:11:?, code lost:
            r3.close();
         */
        /* JADX WARNING: Code restructure failed: missing block: B:12:0x002c, code lost:
            r3 = move-exception;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:13:0x002d, code lost:
            r2.addSuppressed(r3);
         */
        /* JADX WARNING: Code restructure failed: missing block: B:14:0x0030, code lost:
            throw r4;
         */
        /* JADX WARNING: Code restructure failed: missing block: B:9:0x0027, code lost:
            r4 = move-exception;
         */
        /* Code decompiled incorrectly, please refer to instructions dump. */
        public java.io.File ensureThumbnail(android.net.Uri r3, android.os.CancellationSignal r4) throws java.io.IOException {
            /*
                r2 = this;
                java.io.File r0 = r2.getThumbnailFile(r3)
                java.io.File r1 = r0.getParentFile()
                r1.mkdirs()
                boolean r1 = r0.exists()
                if (r1 != 0) goto L_0x0031
                android.graphics.Bitmap r2 = r2.getThumbnailBitmap(r3, r4)
                java.io.FileOutputStream r3 = new java.io.FileOutputStream
                r3.<init>(r0)
                android.graphics.Bitmap$CompressFormat r4 = android.graphics.Bitmap.CompressFormat.JPEG     // Catch:{ all -> 0x0025 }
                r1 = 75
                r2.compress(r4, r1, r3)     // Catch:{ all -> 0x0025 }
                r3.close()
                goto L_0x0031
            L_0x0025:
                r2 = move-exception
                throw r2     // Catch:{ all -> 0x0027 }
            L_0x0027:
                r4 = move-exception
                r3.close()     // Catch:{ all -> 0x002c }
                goto L_0x0030
            L_0x002c:
                r3 = move-exception
                r2.addSuppressed(r3)
            L_0x0030:
                throw r4
            L_0x0031:
                return r0
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.Thumbnailer.ensureThumbnail(android.net.Uri, android.os.CancellationSignal):java.io.File");
        }

        public void invalidateThumbnail(Uri uri) throws IOException {
            getThumbnailFile(uri).delete();
        }
    }

    /* access modifiers changed from: private */
    /* renamed from: invalidateThumbnails */
    public void lambda$updateInternal$7$MediaProvider(Uri uri) {
        Trace.traceBegin(1048576, "invalidateThumbnails");
        try {
            invalidateThumbnailsInternal(uri);
        } finally {
            Trace.traceEnd(1048576);
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:17:0x0057, code lost:
        r8 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:18:0x0058, code lost:
        if (r1 != null) goto L_0x005a;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:19:0x005a, code lost:
        $closeResource(r7, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:20:0x005d, code lost:
        throw r8;
     */
    /* JADX WARNING: Failed to process nested try/catch */
    /* JADX WARNING: Missing exception handler attribute for start block: B:3:0x0013 */
    /* JADX WARNING: Removed duplicated region for block: B:11:0x003f  */
    /* JADX WARNING: Removed duplicated region for block: B:9:0x0035 A[Catch:{ all -> 0x0057 }, LOOP:0: B:6:0x002f->B:9:0x0035, LOOP_END] */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private void invalidateThumbnailsInternal(android.net.Uri r8) {
        /*
        // Method dump skipped, instructions count: 101
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.invalidateThumbnailsInternal(android.net.Uri):void");
    }

    public int update(Uri uri, ContentValues contentValues, String str, String[] strArr) {
        Trace.traceBegin(1048576, "update");
        try {
            return updateInternal(uri, contentValues, str, strArr);
        } finally {
            Trace.traceEnd(1048576);
        }
    }

    /* JADX DEBUG: Multi-variable search result rejected for r2v48, resolved type: java.lang.String */
    /* JADX DEBUG: Multi-variable search result rejected for r2v61, resolved type: java.lang.String */
    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARNING: Code restructure failed: missing block: B:142:0x0351, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:144:0x0353, code lost:
        if (r1 != null) goto L_0x0355;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:145:0x0355, code lost:
        $closeResource(r0, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:146:0x0358, code lost:
        throw r0;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:481:0x0a49, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:483:0x0a4b, code lost:
        if (r2 != null) goto L_0x0a4d;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:484:0x0a4d, code lost:
        $closeResource(r0, r2);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:485:0x0a50, code lost:
        throw r0;
     */
    /* JADX WARNING: Removed duplicated region for block: B:267:0x0553  */
    /* JADX WARNING: Removed duplicated region for block: B:285:0x05fc  */
    /* JADX WARNING: Removed duplicated region for block: B:379:0x0840  */
    /* JADX WARNING: Removed duplicated region for block: B:380:0x0852  */
    /* JADX WARNING: Removed duplicated region for block: B:383:0x085e A[SYNTHETIC, Splitter:B:383:0x085e] */
    /* JADX WARNING: Removed duplicated region for block: B:432:0x09a0 A[Catch:{ all -> 0x0a3b }] */
    /* JADX WARNING: Removed duplicated region for block: B:472:0x0a2d  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private int updateInternal(android.net.Uri r42, android.content.ContentValues r43, java.lang.String r44, java.lang.String[] r45) {
        /*
        // Method dump skipped, instructions count: 2676
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.updateInternal(android.net.Uri, android.content.ContentValues, java.lang.String, java.lang.String[]):int");
    }

    public /* synthetic */ Boolean lambda$updateInternal$6$MediaProvider(Uri uri) {
        return Boolean.valueOf(isPending(uri));
    }

    private int movePlaylistEntry(String str, DatabaseHelper databaseHelper, SQLiteDatabase sQLiteDatabase, long j, int i, int i2) {
        Throwable th;
        Cursor query;
        Cursor cursor;
        int i3;
        int i4;
        if (i == i2) {
            return 0;
        }
        sQLiteDatabase.beginTransaction();
        Cursor cursor2 = null;
        try {
            try {
                query = sQLiteDatabase.query("audio_playlists_map", new String[]{"play_order"}, "playlist_id=?", new String[]{"" + j}, null, null, "play_order", i + ",1");
            } catch (Throwable th2) {
                th = th2;
                cursor2 = null;
                sQLiteDatabase.endTransaction();
                IoUtils.closeQuietly(cursor2);
                throw th;
            }
            try {
                query.moveToFirst();
                i3 = query.getInt(0);
                IoUtils.closeQuietly(query);
                cursor = query;
                try {
                    cursor2 = sQLiteDatabase.query("audio_playlists_map", new String[]{"play_order"}, "playlist_id=?", new String[]{"" + j}, null, null, "play_order", i2 + ",1");
                } catch (Throwable th3) {
                    th = th3;
                    cursor2 = cursor;
                    sQLiteDatabase.endTransaction();
                    IoUtils.closeQuietly(cursor2);
                    throw th;
                }
            } catch (Throwable th4) {
                th = th4;
                cursor = query;
                cursor2 = cursor;
                sQLiteDatabase.endTransaction();
                IoUtils.closeQuietly(cursor2);
                throw th;
            }
            try {
                cursor2.moveToFirst();
                int i5 = cursor2.getInt(0);
                sQLiteDatabase.execSQL("UPDATE audio_playlists_map SET play_order=-1 WHERE play_order=" + i3 + " AND playlist_id=" + j);
                if (i < i2) {
                    sQLiteDatabase.execSQL("UPDATE audio_playlists_map SET play_order=play_order-1 WHERE play_order<=" + i5 + " AND play_order>" + i3 + " AND playlist_id=" + j);
                    i4 = i2 - i;
                } else {
                    sQLiteDatabase.execSQL("UPDATE audio_playlists_map SET play_order=play_order+1 WHERE play_order>=" + i5 + " AND play_order<" + i3 + " AND playlist_id=" + j);
                    i4 = i - i2;
                }
                int i6 = i4 + 1;
                sQLiteDatabase.execSQL("UPDATE audio_playlists_map SET play_order=" + i5 + " WHERE play_order=-1 AND playlist_id=" + j);
                sQLiteDatabase.setTransactionSuccessful();
                sQLiteDatabase.endTransaction();
                IoUtils.closeQuietly(cursor2);
                getContext().getContentResolver().notifyChange(ContentUris.withAppendedId(MediaStore.Audio.Playlists.getContentUri(str), j), null);
                return i6;
            } catch (Throwable th5) {
                th = th5;
                sQLiteDatabase.endTransaction();
                IoUtils.closeQuietly(cursor2);
                throw th;
            }
        } catch (Throwable th6) {
            th = th6;
            sQLiteDatabase.endTransaction();
            IoUtils.closeQuietly(cursor2);
            throw th;
        }
    }

    private void updatePlaylistDateModifiedToNow(SQLiteDatabase sQLiteDatabase, long j) {
        ContentValues contentValues = new ContentValues();
        contentValues.put("date_modified", Long.valueOf(TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis())));
        sQLiteDatabase.update("files", contentValues, "_id=?", new String[]{String.valueOf(j)});
    }

    @Override // android.content.ContentProvider
    public ParcelFileDescriptor openFile(Uri uri, String str) throws FileNotFoundException {
        return openFileCommon(uri, str, null);
    }

    @Override // android.content.ContentProvider
    public ParcelFileDescriptor openFile(Uri uri, String str, CancellationSignal cancellationSignal) throws FileNotFoundException {
        return openFileCommon(uri, str, cancellationSignal);
    }

    private ParcelFileDescriptor openFileCommon(Uri uri, String str, CancellationSignal cancellationSignal) throws FileNotFoundException {
        Uri safeUncanonicalize = safeUncanonicalize(uri);
        int matchUri = matchUri(safeUncanonicalize, isCallingPackageAllowedHidden());
        String volumeName = MediaStore.getVolumeName(safeUncanonicalize);
        if (matchUri == 3) {
            return ParcelFileDescriptor.open(ensureThumbnail(ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volumeName), Long.parseLong(safeUncanonicalize.getPathSegments().get(3))), cancellationSignal), 268435456);
        } else if (matchUri == VIDEO_MEDIA_ID_THUMBNAIL) {
            return ParcelFileDescriptor.open(ensureThumbnail(ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(volumeName), Long.parseLong(safeUncanonicalize.getPathSegments().get(3))), cancellationSignal), 268435456);
        } else if (matchUri == AUDIO_ALBUMART_ID) {
            return ParcelFileDescriptor.open(ensureThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Albums.getContentUri(volumeName), Long.parseLong(safeUncanonicalize.getPathSegments().get(3))), cancellationSignal), 268435456);
        } else if (matchUri != AUDIO_ALBUMART_FILE_ID) {
            return openFileAndEnforcePathPermissionsHelper(safeUncanonicalize, matchUri, str, cancellationSignal);
        } else {
            return ParcelFileDescriptor.open(ensureThumbnail(ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(volumeName), Long.parseLong(safeUncanonicalize.getPathSegments().get(3))), cancellationSignal), 268435456);
        }
    }

    @Override // android.content.ContentProvider
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String str, Bundle bundle) throws FileNotFoundException {
        return openTypedAssetFileCommon(uri, str, bundle, null);
    }

    @Override // android.content.ContentProvider
    public AssetFileDescriptor openTypedAssetFile(Uri uri, String str, Bundle bundle, CancellationSignal cancellationSignal) throws FileNotFoundException {
        return openTypedAssetFileCommon(uri, str, bundle, cancellationSignal);
    }

    private AssetFileDescriptor openTypedAssetFileCommon(Uri uri, String str, Bundle bundle, CancellationSignal cancellationSignal) throws FileNotFoundException {
        Uri safeUncanonicalize = safeUncanonicalize(uri);
        if (!(bundle != null && bundle.containsKey("android.content.extra.SIZE") && str != null && str.startsWith("image/")) || !MediaProviderUtils.isMatchedThumbnailer(matchUri(safeUncanonicalize, isCallingPackageAllowedHidden()))) {
            return new AssetFileDescriptor(openFileCommon(safeUncanonicalize, "r", cancellationSignal), 0, -1);
        }
        return new AssetFileDescriptor(ParcelFileDescriptor.open(ensureThumbnail(safeUncanonicalize, cancellationSignal), 268435456), 0, -1);
    }

    /* JADX WARNING: Code restructure failed: missing block: B:30:0x00a3, code lost:
        r4 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:31:0x00a4, code lost:
        if (r5 != null) goto L_0x00a6;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:32:0x00a6, code lost:
        $closeResource(r14, r5);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:33:0x00a9, code lost:
        throw r4;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private java.io.File ensureThumbnail(android.net.Uri r13, android.os.CancellationSignal r14) throws java.io.FileNotFoundException {
        /*
        // Method dump skipped, instructions count: 233
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.ensureThumbnail(android.net.Uri, android.os.CancellationSignal):java.io.File");
    }

    private void updateImageMetadata(ContentValues contentValues, File file) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        contentValues.put("width", Integer.valueOf(options.outWidth));
        contentValues.put("height", Integer.valueOf(options.outHeight));
    }

    /* access modifiers changed from: package-private */
    public File queryForDataFile(Uri uri, CancellationSignal cancellationSignal) throws FileNotFoundException {
        return queryForDataFile(uri, null, null, cancellationSignal);
    }

    /* access modifiers changed from: package-private */
    /* JADX WARNING: Code restructure failed: missing block: B:13:0x003f, code lost:
        r9 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:14:0x0040, code lost:
        if (r7 != null) goto L_0x0042;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:15:0x0042, code lost:
        $closeResource(r8, r7);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:16:0x0045, code lost:
        throw r9;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public java.io.File queryForDataFile(android.net.Uri r8, java.lang.String r9, java.lang.String[] r10, android.os.CancellationSignal r11) throws java.io.FileNotFoundException {
        /*
            r7 = this;
            java.lang.String r0 = "_data"
            java.lang.String[] r3 = new java.lang.String[]{r0}
            r1 = r7
            r2 = r8
            r4 = r9
            r5 = r10
            r6 = r11
            android.database.Cursor r7 = r1.queryForSingleItem(r2, r3, r4, r5, r6)
            r9 = 0
            java.lang.String r9 = r7.getString(r9)     // Catch:{ all -> 0x003d }
            boolean r10 = android.text.TextUtils.isEmpty(r9)     // Catch:{ all -> 0x003d }
            if (r10 != 0) goto L_0x0026
            java.io.File r8 = new java.io.File     // Catch:{ all -> 0x003d }
            r8.<init>(r9)     // Catch:{ all -> 0x003d }
            if (r7 == 0) goto L_0x0025
            r9 = 0
            $closeResource(r9, r7)
        L_0x0025:
            return r8
        L_0x0026:
            java.io.FileNotFoundException r9 = new java.io.FileNotFoundException
            java.lang.StringBuilder r10 = new java.lang.StringBuilder
            r10.<init>()
            java.lang.String r11 = "Missing path for "
            r10.append(r11)
            r10.append(r8)
            java.lang.String r8 = r10.toString()
            r9.<init>(r8)
            throw r9
        L_0x003d:
            r8 = move-exception
            throw r8     // Catch:{ all -> 0x003f }
        L_0x003f:
            r9 = move-exception
            if (r7 == 0) goto L_0x0045
            $closeResource(r8, r7)
        L_0x0045:
            throw r9
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.queryForDataFile(android.net.Uri, java.lang.String, java.lang.String[], android.os.CancellationSignal):java.io.File");
    }

    /* access modifiers changed from: package-private */
    /* JADX WARNING: Code restructure failed: missing block: B:10:0x0033, code lost:
        if (r8 != null) goto L_0x0035;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:11:0x0035, code lost:
        $closeResource(r9, r8);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:12:0x0038, code lost:
        throw r10;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:9:0x0032, code lost:
        r10 = move-exception;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public android.net.Uri queryForMediaUri(java.io.File r9, android.os.CancellationSignal r10) throws java.io.FileNotFoundException {
        /*
            r8 = this;
            java.lang.String r0 = android.provider.MediaStore.getVolumeName(r9)
            android.net.Uri r0 = android.provider.MediaStore.Files.getContentUri(r0)
            java.lang.String r1 = "_id"
            java.lang.String[] r3 = new java.lang.String[]{r1}
            r1 = 1
            java.lang.String[] r5 = new java.lang.String[r1]
            java.lang.String r9 = r9.getAbsolutePath()
            r7 = 0
            r5[r7] = r9
            java.lang.String r4 = "_data=?"
            r1 = r8
            r2 = r0
            r6 = r10
            android.database.Cursor r8 = r1.queryForSingleItem(r2, r3, r4, r5, r6)
            long r9 = r8.getLong(r7)     // Catch:{ all -> 0x0030 }
            android.net.Uri r9 = android.content.ContentUris.withAppendedId(r0, r9)     // Catch:{ all -> 0x0030 }
            if (r8 == 0) goto L_0x002f
            r10 = 0
            $closeResource(r10, r8)
        L_0x002f:
            return r9
        L_0x0030:
            r9 = move-exception
            throw r9     // Catch:{ all -> 0x0032 }
        L_0x0032:
            r10 = move-exception
            if (r8 == 0) goto L_0x0038
            $closeResource(r9, r8)
        L_0x0038:
            throw r10
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.queryForMediaUri(java.io.File, android.os.CancellationSignal):android.net.Uri");
    }

    /* access modifiers changed from: package-private */
    public Cursor queryForSingleItem(Uri uri, String[] strArr, String str, String[] strArr2, CancellationSignal cancellationSignal) throws FileNotFoundException {
        Cursor query = query(uri, strArr, ContentResolver.createSqlQueryBundle(str, strArr2, null), cancellationSignal);
        if (query == null) {
            throw new FileNotFoundException("Missing cursor for " + uri);
        } else if (query.getCount() < 1) {
            IoUtils.closeQuietly(query);
            throw new FileNotFoundException("No item at " + uri);
        } else if (query.getCount() > 1) {
            IoUtils.closeQuietly(query);
            throw new FileNotFoundException("Multiple items at " + uri);
        } else if (query.moveToFirst()) {
            return query;
        } else {
            IoUtils.closeQuietly(query);
            throw new FileNotFoundException("Failed to read row from " + uri);
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:121:0x01f6, code lost:
        r13 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:122:0x01f7, code lost:
        if (r15 != null) goto L_0x01f9;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:123:0x01f9, code lost:
        $closeResource(r12, r15);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:124:0x01fc, code lost:
        throw r13;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private android.os.ParcelFileDescriptor openFileAndEnforcePathPermissionsHelper(android.net.Uri r12, int r13, java.lang.String r14, android.os.CancellationSignal r15) throws java.io.FileNotFoundException {
        /*
        // Method dump skipped, instructions count: 526
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.openFileAndEnforcePathPermissionsHelper(android.net.Uri, int, java.lang.String, android.os.CancellationSignal):android.os.ParcelFileDescriptor");
    }

    public /* synthetic */ void lambda$openFileAndEnforcePathPermissionsHelper$8$MediaProvider(Uri uri, int i, File file, IOException iOException) {
        lambda$updateInternal$7$MediaProvider(uri);
        if (i == IMAGES_THUMBNAILS_ID || i == VIDEO_THUMBNAILS_ID) {
            ContentValues contentValues = new ContentValues();
            updateImageMetadata(contentValues, file);
            update(uri, contentValues, null, null);
            return;
        }
        try {
            MediaScanner.instance(getContext()).scanFile(file);
        } catch (Exception e) {
            Log.w(TAG, "Failed to update metadata for " + uri, e);
        }
    }

    private void deleteIfAllowed(Uri uri, String str) {
        if (!MediaProviderUtils.isUnmountedVolumeChecking(str)) {
            try {
                File file = new File(str);
                checkAccess(uri, file, true);
                if (file.delete() && DBG) {
                    Log.d(TAG, "deleteIfAllowed: path = " + str);
                }
            } catch (Exception e) {
                Log.e(TAG, "Couldn't delete " + str, e);
            }
        } else if (LOCAL_LOGV) {
            Log.d(TAG, "deleteIfAllowed: skip on deleting file " + str);
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:21:0x0032, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:22:0x0033, code lost:
        if (r10 != null) goto L_0x0035;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:23:0x0035, code lost:
        $closeResource(r11, r10);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:24:0x0038, code lost:
        throw r0;
     */
    @java.lang.Deprecated
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private boolean isPending(android.net.Uri r11) {
        /*
            r10 = this;
            r0 = 1
            int r1 = matchUri(r11, r0)
            r2 = 2
            r3 = 0
            if (r1 == r2) goto L_0x0012
            r2 = 101(0x65, float:1.42E-43)
            if (r1 == r2) goto L_0x0012
            r2 = 201(0xc9, float:2.82E-43)
            if (r1 == r2) goto L_0x0012
            return r3
        L_0x0012:
            java.lang.String r1 = "is_pending"
            java.lang.String[] r6 = new java.lang.String[]{r1}     // Catch:{ FileNotFoundException -> 0x0039 }
            r7 = 0
            r8 = 0
            r9 = 0
            r4 = r10
            r5 = r11
            android.database.Cursor r10 = r4.queryForSingleItem(r5, r6, r7, r8, r9)     // Catch:{ FileNotFoundException -> 0x0039 }
            r11 = 0
            int r1 = r10.getInt(r3)     // Catch:{ all -> 0x0030 }
            if (r1 == 0) goto L_0x0029
            goto L_0x002a
        L_0x0029:
            r0 = r3
        L_0x002a:
            if (r10 == 0) goto L_0x002f
            $closeResource(r11, r10)
        L_0x002f:
            return r0
        L_0x0030:
            r11 = move-exception
            throw r11     // Catch:{ all -> 0x0032 }
        L_0x0032:
            r0 = move-exception
            if (r10 == 0) goto L_0x0038
            $closeResource(r11, r10)
        L_0x0038:
            throw r0
        L_0x0039:
            r10 = move-exception
            java.lang.IllegalStateException r11 = new java.lang.IllegalStateException
            r11.<init>(r10)
            throw r11
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.isPending(android.net.Uri):boolean");
    }

    @Deprecated
    private boolean isRedactionNeeded(Uri uri) {
        return this.mCallingIdentity.get().hasPermission(4);
    }

    /* access modifiers changed from: private */
    public static final class RedactionInfo {
        public final long[] freeOffsets;
        public final long[] redactionRanges;

        public RedactionInfo(long[] jArr, long[] jArr2) {
            this.redactionRanges = jArr;
            this.freeOffsets = jArr2;
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:24:0x00a4, code lost:
        r0 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:25:0x00a5, code lost:
        $closeResource(r0, r6);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:26:0x00a9, code lost:
        throw r0;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private com.android.providers.media.MediaProvider.RedactionInfo getRedactionRanges(java.io.File r21) {
        /*
        // Method dump skipped, instructions count: 218
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.getRedactionRanges(java.io.File):com.android.providers.media.MediaProvider$RedactionInfo");
    }

    private boolean checkCallingPermissionGlobal(Uri uri, boolean z) {
        if (isCallingPackageSystem()) {
            return true;
        }
        int matchUri = matchUri(uri, true);
        int i = 2;
        if (matchUri == 2 || matchUri == AUDIO_MEDIA_ID || matchUri == VIDEO_MEDIA_ID || matchUri == FILES_ID || matchUri == DOWNLOADS_ID) {
            if (this.mCallingIdentity.get().isOwned(ContentUris.parseId(uri))) {
                return true;
            }
        }
        Context context = getContext();
        int i2 = this.mCallingIdentity.get().pid;
        int i3 = this.mCallingIdentity.get().uid;
        if (!z) {
            i = 1;
        }
        if (context.checkUriPermission(uri, i2, i3, i) == 0) {
            return true;
        }
        return false;
    }

    private boolean checkCallingPermissionLegacy(Uri uri, boolean z, String str) {
        return this.mCallingIdentity.get().hasPermission(2);
    }

    @Deprecated
    private boolean checkCallingPermissionAudio(boolean z, String str) {
        if (z) {
            return this.mCallingIdentity.get().hasPermission(64);
        }
        return this.mCallingIdentity.get().hasPermission(8);
    }

    @Deprecated
    private boolean checkCallingPermissionVideo(boolean z, String str) {
        if (z) {
            return this.mCallingIdentity.get().hasPermission(LocalCallingIdentity.PERMISSION_WRITE_VIDEO);
        }
        return this.mCallingIdentity.get().hasPermission(16);
    }

    @Deprecated
    private boolean checkCallingPermissionImages(boolean z, String str) {
        if (z) {
            return this.mCallingIdentity.get().hasPermission(LocalCallingIdentity.PERMISSION_WRITE_IMAGES);
        }
        return this.mCallingIdentity.get().hasPermission(32);
    }

    private void enforceCallingPermission(Uri uri, boolean z) {
        Trace.traceBegin(1048576, "enforceCallingPermission");
        try {
            enforceCallingPermissionInternal(uri, z);
        } finally {
            Trace.traceEnd(1048576);
        }
    }

    /* JADX WARNING: Code restructure failed: missing block: B:18:0x0040, code lost:
        r14 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:19:0x0041, code lost:
        if (r1 != null) goto L_0x0043;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:20:0x0043, code lost:
        $closeResource(r13, r1);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:21:0x0046, code lost:
        throw r14;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:47:0x00ec, code lost:
        r14 = move-exception;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:48:0x00ed, code lost:
        if (r0 != null) goto L_0x00ef;
     */
    /* JADX WARNING: Code restructure failed: missing block: B:49:0x00ef, code lost:
        $closeResource(r13, r0);
     */
    /* JADX WARNING: Code restructure failed: missing block: B:50:0x00f2, code lost:
        throw r14;
     */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private void enforceCallingPermissionInternal(android.net.Uri r14, boolean r15) {
        /*
        // Method dump skipped, instructions count: 249
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.enforceCallingPermissionInternal(android.net.Uri, boolean):void");
    }

    private Icon getCollectionIcon(Uri uri) {
        PackageManager packageManager = getContext().getPackageManager();
        uri.getPathSegments().get(1).hashCode();
        try {
            PermissionGroupInfo permissionGroupInfo = packageManager.getPermissionGroupInfo("android.permission-group.STORAGE", 0);
            return Icon.createWithResource(permissionGroupInfo.packageName, permissionGroupInfo.icon);
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkAccess(Uri uri, File file, boolean z) throws FileNotFoundException {
        enforceCallingPermission(uri, z);
        if (!FileUtils.contains(Environment.getStorageDirectory(), file)) {
            checkWorldReadAccess(file.getAbsolutePath());
        }
    }

    private static void checkWorldReadAccess(String str) throws FileNotFoundException {
        int i;
        if (str.startsWith("/storage/")) {
            i = OsConstants.S_IRGRP;
        } else {
            i = OsConstants.S_IROTH;
        }
        try {
            StructStat stat = Os.stat(str);
            if (OsConstants.S_ISREG(stat.st_mode) && (stat.st_mode & i) == i) {
                checkLeadingPathComponentsWorldExecutable(str);
                return;
            }
        } catch (ErrnoException unused) {
        }
        throw new FileNotFoundException("Can't access " + str);
    }

    private static void checkLeadingPathComponentsWorldExecutable(String str) throws FileNotFoundException {
        int i;
        if (str.startsWith("/storage/")) {
            i = OsConstants.S_IXGRP;
        } else {
            i = OsConstants.S_IXOTH;
        }
        for (File parentFile = new File(str).getParentFile(); parentFile != null; parentFile = parentFile.getParentFile()) {
            if (parentFile.exists()) {
                try {
                    if ((Os.stat(parentFile.getPath()).st_mode & i) != i) {
                        throw new FileNotFoundException("Can't access " + str);
                    }
                } catch (ErrnoException unused) {
                    throw new FileNotFoundException("Can't access " + str);
                }
            } else {
                throw new FileNotFoundException("access denied");
            }
        }
    }

    private long getKeyIdForName(DatabaseHelper databaseHelper, SQLiteDatabase sQLiteDatabase, String str, String str2, String str3, String str4, String str5, String str6, int i, String str7, ArrayMap<String, Long> arrayMap, Uri uri) {
        long j;
        String str8 = (str4 == null || str4.length() == 0) ? "<unknown>" : str4;
        String keyFor = MediaStore.Audio.keyFor(str8);
        if (keyFor == null) {
            Log.e(TAG, "null key", new Exception());
            return -1;
        }
        boolean z = str.equals("albums") && !MediaProviderRingtoneUtils.isRingtonePath(str6);
        boolean equals = "<unknown>".equals(str8);
        if (z) {
            keyFor = keyFor + i;
            if (equals) {
                keyFor = keyFor + str7;
            }
        }
        Cursor query = sQLiteDatabase.query(str, null, str2 + "=?", new String[]{keyFor}, null, null, null);
        try {
            int count = query.getCount();
            if (count == 0) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(str2, keyFor);
                contentValues.put(str3, str8);
                j = sQLiteDatabase.insert(str, "duration", contentValues);
                if (j > 0) {
                    getContext().getContentResolver().notifyChange(Uri.parse("content://media/" + uri.toString().substring(16, 24) + "/audio/" + str + "/" + j), null);
                }
            } else if (count != 1) {
                Log.e(TAG, "Multiple entries in table " + str + " for key " + keyFor);
                j = -1;
            } else {
                query.moveToFirst();
                j = query.getLong(0);
                String string = query.getString(2);
                String makeBestName = makeBestName(str8, string);
                if (!makeBestName.equals(string)) {
                    ContentValues contentValues2 = new ContentValues();
                    contentValues2.put(str3, makeBestName);
                    sQLiteDatabase.update(str, contentValues2, "rowid=" + Integer.toString((int) j), null);
                    getContext().getContentResolver().notifyChange(Uri.parse("content://media/" + uri.toString().substring(16, 24) + "/audio/" + str + "/" + j), null);
                    if (z) {
                        arrayMap.remove(string + i);
                    } else {
                        arrayMap.remove(string);
                    }
                }
            }
            if (arrayMap != null && !equals) {
                arrayMap.put(str5, Long.valueOf(j));
            }
            return j;
        } finally {
            IoUtils.closeQuietly(query);
        }
    }

    /* access modifiers changed from: package-private */
    public String makeBestName(String str, String str2) {
        if (str.length() <= str2.length() && str.toLowerCase().compareTo(str2.toLowerCase()) < 0) {
            str = str2;
        }
        if (!str.endsWith(", the") && !str.endsWith(",the") && !str.endsWith(", an") && !str.endsWith(",an") && !str.endsWith(", a") && !str.endsWith(",a")) {
            return str;
        }
        String substring = str.substring(str.lastIndexOf(44) + 1);
        return substring.trim() + " " + str.substring(0, str.lastIndexOf(44));
    }

    /* access modifiers changed from: private */
    public static class FallbackException extends Exception {
        public FallbackException(String str) {
            super(str);
        }

        public IllegalArgumentException rethrowAsIllegalArgumentException() {
            throw new IllegalArgumentException(getMessage());
        }

        public Cursor translateForQuery(int i) {
            if (i < 29) {
                Log.w(MediaProvider.TAG, getMessage());
                return null;
            }
            throw new IllegalArgumentException(getMessage());
        }

        public Uri translateForInsert(int i) {
            if (i < 29) {
                Log.w(MediaProvider.TAG, getMessage());
                return null;
            }
            throw new IllegalArgumentException(getMessage());
        }

        public int translateForUpdateDelete(int i) {
            if (i < 29) {
                Log.w(MediaProvider.TAG, getMessage());
                return 0;
            }
            throw new IllegalArgumentException(getMessage());
        }
    }

    /* access modifiers changed from: package-private */
    public static class VolumeNotFoundException extends FallbackException {
        public VolumeNotFoundException(String str) {
            super("Volume " + str + " not found");
        }
    }

    /* access modifiers changed from: package-private */
    public static class VolumeArgumentException extends FallbackException {
        public VolumeArgumentException(File file, Collection<File> collection) {
            super("Requested path " + file + " doesn't appear under " + collection);
        }
    }

    private DatabaseHelper getDatabaseForUri(Uri uri) throws VolumeNotFoundException {
        String resolveVolumeName = resolveVolumeName(uri);
        synchronized (this.mAttachedVolumeNames) {
            if (!this.mAttachedVolumeNames.contains(resolveVolumeName)) {
                throw new VolumeNotFoundException(resolveVolumeName);
            }
        }
        if ("internal".equals(resolveVolumeName)) {
            return this.mInternalDatabase;
        }
        return this.mExternalDatabase;
    }

    static boolean isMediaDatabaseName(String str) {
        if (INTERNAL_DATABASE_NAME.equals(str) || EXTERNAL_DATABASE_NAME.equals(str)) {
            return true;
        }
        if (!str.startsWith("external-") || !str.endsWith(".db")) {
            return false;
        }
        return true;
    }

    static boolean isInternalMediaDatabaseName(String str) {
        return INTERNAL_DATABASE_NAME.equals(str);
    }

    private void attachVolume(Uri uri) {
        attachVolume(MediaStore.getVolumeName(uri));
    }

    public Uri attachVolume(String str) {
        if (this.mCallingIdentity.get().pid == Process.myPid()) {
            MediaStore.checkArgumentVolumeName(str);
            if (!"internal".equals(str)) {
                try {
                    getVolumePath(str);
                } catch (IOException e) {
                    throw new IllegalArgumentException("Volume " + str + " currently unavailable", e);
                }
            }
            synchronized (this.mAttachedVolumeNames) {
                this.mAttachedVolumeNames.add(str);
            }
            Uri build = MediaStore.AUTHORITY_URI.buildUpon().appendPath(str).build();
            getContext().getContentResolver().notifyChange(build, null);
            if (LOCAL_LOGV) {
                Log.v(TAG, "Attached volume: " + str);
            }
            if (!"internal".equals(str)) {
                DatabaseHelper databaseHelper = this.mInternalDatabase;
                ensureDefaultFolders(str, databaseHelper, databaseHelper.getWritableDatabase());
            }
            return build;
        }
        throw new SecurityException("Opening and closing databases not allowed.");
    }

    private void detachVolume(Uri uri) {
        detachVolume(MediaStore.getVolumeName(uri));
    }

    public void detachVolume(String str) {
        if (this.mCallingIdentity.get().pid == Process.myPid()) {
            MediaStore.checkArgumentVolumeName(str);
            if (!"internal".equals(str)) {
                MediaScanner.instance(getContext()).onDetachVolume(str);
                if (MediaProviderUtils.isDeviceShuttingDown()) {
                    Log.d(TAG, "not deleting entries on eject due to shutdown");
                    return;
                }
                synchronized (this.mAttachedVolumeNames) {
                    this.mAttachedVolumeNames.remove(str);
                }
                getContext().getContentResolver().notifyChange(MediaStore.AUTHORITY_URI.buildUpon().appendPath(str).build(), null);
                if (LOCAL_LOGV) {
                    Log.v(TAG, "Detached volume: " + str);
                    return;
                }
                return;
            }
            throw new UnsupportedOperationException("Deleting the internal volume is not allowed");
        }
        throw new SecurityException("Opening and closing databases not allowed.");
    }

    private static int matchUri(Uri uri, boolean z) {
        int match = PUBLIC_URI_MATCHER.match(uri);
        if (match != -1) {
            return match;
        }
        int match2 = HIDDEN_URI_MATCHER.match(uri);
        if (match2 == -1) {
            return -1;
        }
        if (z) {
            return match2;
        }
        throw new IllegalStateException("Unknown URL: " + uri + " is hidden API");
    }

    private static void addGreylistPattern(String str) {
        ArrayList<Pattern> arrayList = sGreylist;
        arrayList.add(Pattern.compile(" *" + str + " *"));
    }

    static ArrayMap<String, String> getProjectionMap(Class<?> cls) {
        ArrayMap<String, String> arrayMap;
        synchronized (sProjectionMapCache) {
            arrayMap = sProjectionMapCache.get(cls);
            if (arrayMap == null) {
                arrayMap = new ArrayMap<>();
                sProjectionMapCache.put(cls, arrayMap);
                try {
                    Field[] fields = cls.getFields();
                    for (Field field : fields) {
                        if (field.isAnnotationPresent(Column.class)) {
                            String str = (String) field.get(null);
                            arrayMap.put(str, str);
                        }
                    }
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return arrayMap;
    }

    @VisibleForTesting
    static String maybeBalance(String str) {
        if (str == null) {
            return null;
        }
        int i = 0;
        char c = 0;
        for (int i2 = 0; i2 < str.length(); i2++) {
            char charAt = str.charAt(i2);
            if (charAt == '\'' || charAt == '\"') {
                if (c == 0) {
                    c = charAt;
                } else if (c == charAt) {
                    c = 0;
                }
            }
            if (c == 0) {
                if (charAt == '(') {
                    i++;
                } else if (charAt == ')') {
                    i--;
                }
            }
        }
        while (i > 0) {
            str = str + ")";
            i--;
        }
        while (i < 0) {
            str = "(" + str;
            i++;
        }
        return str;
    }

    static <T> boolean containsAny(Set<T> set, Set<T> set2) {
        for (T t : set2) {
            if (set.contains(t)) {
                return true;
            }
        }
        return false;
    }

    @VisibleForTesting
    static Pair<String, String> recoverAbusiveGroupBy(Pair<String, String> pair) {
        String str = (String) pair.first;
        String str2 = (String) pair.second;
        int indexOf = str != null ? str.toUpperCase().indexOf(" GROUP BY ") : -1;
        if (indexOf == -1) {
            return pair;
        }
        String substring = str.substring(0, indexOf);
        String substring2 = str.substring(indexOf + 10);
        String maybeBalance = maybeBalance(substring);
        String maybeBalance2 = maybeBalance(substring2);
        if (TextUtils.isEmpty(str2)) {
            Log.w(TAG, "Recovered abusive '" + maybeBalance + "' and '" + maybeBalance2 + "' from '" + str + "'");
            return Pair.create(maybeBalance, maybeBalance2);
        }
        throw new IllegalArgumentException("Abusive '" + maybeBalance2 + "' conflicts with requested '" + str2 + "'");
    }

    @VisibleForTesting
    static Uri computeCommonPrefix(List<Uri> list) {
        if (list.isEmpty()) {
            return null;
        }
        Uri uri = list.get(0);
        ArrayList arrayList = new ArrayList(uri.getPathSegments());
        for (int i = 1; i < list.size(); i++) {
            List<String> pathSegments = list.get(i).getPathSegments();
            int i2 = 0;
            while (i2 < arrayList.size() && i2 < pathSegments.size()) {
                if (!Objects.equals(arrayList.get(i2), pathSegments.get(i2))) {
                    while (arrayList.size() > i2) {
                        arrayList.remove(i2);
                    }
                }
                i2++;
            }
            int size = pathSegments.size();
            while (arrayList.size() > size) {
                arrayList.remove(size);
            }
        }
        Uri.Builder path = uri.buildUpon().path(null);
        for (int i3 = 0; i3 < arrayList.size(); i3++) {
            path.appendPath((String) arrayList.get(i3));
        }
        return path.build();
    }

    @Deprecated
    private String getCallingPackageOrSelf() {
        return this.mCallingIdentity.get().getPackageName();
    }

    @Deprecated
    private int getCallingPackageTargetSdkVersion() {
        return this.mCallingIdentity.get().getTargetSdkVersion();
    }

    @Deprecated
    private boolean isCallingPackageAllowedHidden() {
        return isCallingPackageSystem();
    }

    @Deprecated
    private boolean isCallingPackageSystem() {
        return this.mCallingIdentity.get().hasPermission(1);
    }

    @Deprecated
    private boolean isCallingPackageLegacy() {
        return this.mCallingIdentity.get().hasPermission(2);
    }

    public void dump(FileDescriptor fileDescriptor, PrintWriter printWriter, String[] strArr) {
        IndentingPrintWriter indentingPrintWriter = new IndentingPrintWriter(printWriter, "  ");
        indentingPrintWriter.printPair("mThumbSize", this.mThumbSize);
        indentingPrintWriter.println();
        indentingPrintWriter.printPair("mAttachedVolumeNames", this.mAttachedVolumeNames);
        indentingPrintWriter.println();
        indentingPrintWriter.println(dump(this.mInternalDatabase, true));
        indentingPrintWriter.println(dump(this.mExternalDatabase, true));
    }

    /* JADX WARNING: Removed duplicated region for block: B:12:0x007d  */
    /* JADX WARNING: Removed duplicated region for block: B:24:0x0106  */
    /* JADX WARNING: Removed duplicated region for block: B:34:0x0152  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private java.lang.String dump(com.android.providers.media.MediaProvider.DatabaseHelper r12, boolean r13) {
        /*
        // Method dump skipped, instructions count: 394
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.providers.media.MediaProvider.dump(com.android.providers.media.MediaProvider$DatabaseHelper, boolean):java.lang.String");
    }
}