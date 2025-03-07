/*
 * Copyright (C) 2022 GrapheneOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.permissioncontroller.sscopes;

import android.Manifest;
import android.app.AppOpsManager;
import android.app.Application;
import android.app.StorageScope;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.GosPackageState;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.system.Os;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.permissioncontroller.permission.utils.KotlinUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StorageScopesUtils {
    private static final String TAG = "StorageScopesUtils";

    private static final String MEDIA_PROVIDER_PACKAGE = "com.android.providers.media.module";

    private static final int MAX_SCOPE_PATH_LENGTH_IN_UTF8_BYTES = 4096; // default PATH_MAX value

    private StorageScopesUtils() {}

    private static boolean validateScopePath(@Nullable String path, Context context, @Nullable List<StorageVolume> cachedVolumes) {
        if (path == null) {
            return false;
        }

        // Java String content isn't guaranteed to be a valid UTF-16 sequence
        if (!isUtf16(path)) {
            return false;
        }

        if (path.indexOf('\0') >= 0) {
            return false;
        }

        String[] components = path.split("/");

        if (components.length < 2) {
            return false;
        }

        if (components[0].length() != 0) {
            // first component ("" before initial "/") should be empty
            return false;
        }

        for (int i = 1; i < components.length; ++i) {
            if (components[i].length() == 0) {
                return false;
            }
        }

        if (path.getBytes(StandardCharsets.UTF_8).length > MAX_SCOPE_PATH_LENGTH_IN_UTF8_BYTES) {
            return false;
        }

        if (StorageScopesUtils.storageVolumeForPath(context, new File(path), cachedVolumes) == null) {
            return false;
        }

        return true;
    }

    static String pathToUiLabel(Context ctx, List<StorageVolume> volumes, File path) {
        StorageVolume volume = storageVolumeForPath(ctx, path, volumes);

        if (volume != null) {
            String volumeName = volume.isPrimary() ?
                    ctx.getString(com.android.permissioncontroller.R.string.sscopes_main_storage) :
                    volume.getDescription(ctx);

            File volumePath = volume.getDirectory();

            if (volumePath.equals(path)) {
                return volumeName;
            }
            return volumeName + path.getAbsolutePath().substring(volumePath.getAbsolutePath().length());
        }

        return path.getAbsolutePath();
    }

    @Nullable
    private static StorageVolume storageVolumeForPath(Context ctx, File file, List<StorageVolume> cachedVolumes) {
        List<StorageVolume> volumes = (cachedVolumes != null) ?
                cachedVolumes :
                ctx.getSystemService(StorageManager.class).getStorageVolumes();

        for (StorageVolume volume : volumes) {
            File volumeRoot = volume.getDirectory();

            if (volumeRoot == null) {
                continue;
            }

            if (dirContainsOrEquals(volumeRoot, file)) {
                return volume;
            }
        }
        return null;
    }

    private static boolean dirContainsOrEquals(File dir, File file) {
        for (;;) {
            if (file.equals(dir)) {
                return true;
            }
            file = file.getParentFile();
            if (file == null) {
                return false;
            }
        }
    }

    static String dirUriToPath(Context ctx, Uri uri) {
        final String authority = DocumentsContract.EXTERNAL_STORAGE_PROVIDER_AUTHORITY;

        if (!authority.equals(uri.getAuthority())) {
            Log.e(TAG, "unknown uri " + uri);
            return null;
        }

        Bundle res = null;
        try {
            res = ctx.getContentResolver().call(authority,
                    StorageScope.EXTERNAL_STORAGE_PROVIDER_METHOD_CONVERT_DOC_ID_TO_PATH,
                    DocumentsContract.getTreeDocumentId(uri), null);
        } catch (Exception e) {
            Log.d(TAG, "unable to convert uri " + uri + " to path", e);
        }

        if (res == null) {
            return null;
        }

        final String EXTRA_RESULT = "result"; // DocumentsContract.EXTRA_RESULT is {@hide}
        String path = res.getString(EXTRA_RESULT);

        if (validateScopePath(path, ctx, null)) {
            return path;
        }

        return null;
    }

    static String fileUriToPath(Context ctx, Uri uri, List<StorageVolume> cachedVolumes) {
        String unverifiedPath = null;

        if (isLocalPhotoPickerUri(uri)) {
            ContentResolver cr = ctx.getContentResolver();
            String id = uri.getLastPathSegment();
            var res = cr.call(MediaStore.AUTHORITY, StorageScope.MEDIA_PROVIDER_METHOD_MEDIA_ID_TO_FILE_PATH, id, null);
            if (res != null) {
                unverifiedPath = res.getString(id);
            }
        } else {
            try (ParcelFileDescriptor pfd = ctx.getContentResolver().openFile(uri, "r", null)) {
                String fdPath = "/proc/self/fd/" + pfd.getFd();

                String realpath = Os.readlink(fdPath);

                if (realpath.startsWith("/mnt/user/")) {
                    // devices that launched with Android 11+ mount shared storage differently
                    realpath = realpath.replaceFirst("/mnt/user/" + UserHandle.myUserId() + "/", "/storage/");
                }

                if (new File(realpath).isFile()) {
                    unverifiedPath = realpath;
                } else {
                    Log.d(TAG, realpath + " is not a file, " + Os.stat(realpath));
                }
            } catch (Exception e) {
                Log.d(TAG, "unable to convert uri " + uri + " to path", e);
                return null;
            }
        }

        if (validateScopePath(unverifiedPath, ctx, cachedVolumes)) {
            String path = unverifiedPath;
            return path;
        }

        Log.d(TAG, "invalid path " + unverifiedPath);
        return null;
    }

    private static boolean isLocalPhotoPickerUri(Uri uri) {
        if (!MediaStore.AUTHORITY.equals(uri.getAuthority())) {
            return false;
        }

        List<String> pathSegments = uri.getPathSegments();

        if (pathSegments.size() != 5) {
            return false;
        }

        return "picker".equals(pathSegments.get(0))
                && "com.android.providers.media.photopicker".equals(pathSegments.get(2))
                && "media".equals(pathSegments.get(3));
    }

    static <T> ArrayList<T> arrayListOf(T[] array) {
        return new ArrayList<>(Arrays.asList(array));
    }

    public static boolean storageScopesEnabled(String pkgName) {
        return storageScopesEnabled(GosPackageState.get(pkgName));
    }

    public static boolean storageScopesEnabled(@Nullable GosPackageState ps) {
        return ps != null && ps.hasFlag(GosPackageState.FLAG_STORAGE_SCOPES_ENABLED);
    }

    @Nullable
    static StorageScope[] getStorageScopes(String pkgName) {
        GosPackageState s = GosPackageState.get(pkgName);
        if (!storageScopesEnabled(s)) {
            return null;
        }
        return StorageScope.deserializeArray(s);
    }

    static String getFullLabelForPackage(Application app, String[] uidPkgs, UserHandle user) {
        StringBuilder res = new StringBuilder();

        for (int i = 0; i < uidPkgs.length; ++i) {
            if (i != 0) {
                res.append('\n');
            }

            res.append("• ");
            res.append(KotlinUtils.INSTANCE.getPackageLabel(app, uidPkgs[i], user));
        }

        return res.toString();
    }

    // returns whether at least one permission was revoked
    static boolean revokeStoragePermissions(Context ctx, String pkgName) {
        PackageManager pm = ctx.getPackageManager();

        int uid;
        int targetSdk;
        try {
            uid = pm.getPackageUid(pkgName, 0);
            var flags = PackageManager.ApplicationInfoFlags.of(0);
            targetSdk = pm.getApplicationInfo(pkgName, flags).targetSdkVersion;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }

        String[] perms = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
        };

        AppOpsManager appOps = ctx.getSystemService(AppOpsManager.class);
        // unsafeCheckOpNoThrow is a better option than noteOpNoThrow for this particular use-case

        UserHandle user = Process.myUserHandle();

        int numOfRevokations = 0;

        for (String permission : perms) {
            if (targetSdk >= 23) { // runtime permissions are always granted for targetSdk < 23 apps
                if (pm.checkPermission(permission, pkgName) == PackageManager.PERMISSION_GRANTED) {
                    pm.revokeRuntimePermission(pkgName, permission, user, "StorageScopes");
                    int permFlag = PackageManager.FLAG_PERMISSION_USER_SET;
                    pm.updatePermissionFlags(permission, pkgName, permFlag, permFlag, user);
                    ++ numOfRevokations;
                }
            }

            String op = AppOpsManager.permissionToOp(permission);
            if (op != null
                    && AppOpsManager.opToDefaultMode(op) != AppOpsManager.MODE_ALLOWED
                    && appOps.unsafeCheckOpNoThrow(op, uid, pkgName) == AppOpsManager.MODE_ALLOWED) {
                appOps.setUidMode(op, uid, AppOpsManager.MODE_IGNORED);
                ++ numOfRevokations;
            }
        }

        String[] opPerms = {
                Manifest.permission.MANAGE_EXTERNAL_STORAGE,
                Manifest.permission.MANAGE_MEDIA,
         };

        for (String opPerm : opPerms) {
            String op = AppOpsManager.permissionToOp(opPerm);

            if (appOps.unsafeCheckOpNoThrow(op, uid, pkgName) == AppOpsManager.MODE_ALLOWED) {
                appOps.setUidMode(op, uid, AppOpsManager.MODE_ERRORED);
                ++ numOfRevokations;
            }
        }

        return numOfRevokations != 0;
    }

    static boolean isUtf16(String str) {
        return isUtf16(str, 0, str.length());
    }

    static boolean isUtf16(String str, int a, int b) {
        while (a != b) {
            int c1 = str.charAt(a++);

            if (c1 < 0xd800 || c1 > 0xdfff) {
                continue;
            }

            // check validity of a surrogate pair

            // d800..dbff
            if (c1 <= 0xdbff && a != b) {
                int c2 = str.charAt(a++);
                if (c2 >= 0xdc00 && c2 <= 0xdfff) {
                    continue;
                }
            }
            return false;
        }
        return true;
    }
}
