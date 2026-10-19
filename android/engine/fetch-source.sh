#!/usr/bin/env bash
set -euo pipefail

REV=40282a7bf4500948cfd598fc67e6e63114b26dd9
URL="https://github.com/Black00Z/Blacks-BlackBox/archive/${REV}.tar.gz"
SHA256="aa17025db768bf23f52d19050949bd6baf5a529e6186060d89315515cdb8ea61"
# Dobby pinned to the last commit BEFORE the "Refactor? Yes" change (a588a0d) that removed
# core/arch/Cpu.h while leaving dangling #includes. Both that refactor and the rolling
# master/"latest" (5dfc854) fail to compile. e9fe7fb is the self-consistent parent:
# code-patch-tool-posix.cc -> core/arch/Cpu.h resolves, and it builds for Android arm64.
DOBBY_REV=e9fe7fbecae47a2287e761080f8b1133cc22e8fa
DOBBY_URL="https://github.com/jmpews/Dobby/archive/${DOBBY_REV}.tar.gz"
DOBBY_SHA256="b5dddb530ae3b1abe8b61dd3a690eaf0ffe74900b0313c3d1bee0597691cf2d3"
ROOT="$(cd "$(dirname "$0")" && pwd)"
VENDOR="$ROOT/vendor"
mkdir -p "$VENDOR"

fetch() {
  local url="$1" out="$2" expected="$3"
  curl --fail --location --retry 3 --output "$out" "$url"
  echo "$expected  $out" | sha256sum --check --status
}

archive="$VENDOR/blacks-${REV}.tar.gz"
dobby_archive="$VENDOR/dobby-${DOBBY_REV}.tar.gz"
[[ -f "$archive" ]] || curl --fail --location --retry 3 --output "$archive" "$URL"
echo "$SHA256  $archive" | sha256sum --check --status
[[ -f "$dobby_archive" ]] || curl --fail --location --retry 3 --output "$dobby_archive" "$DOBBY_URL"
echo "$DOBBY_SHA256  $dobby_archive" | sha256sum --check --status

if ! rm -rf "$VENDOR/source" "$VENDOR/dobby"; then
  # On the Windows-mounted OmniRush workspace, a prior WSL extraction can leave root-owned
  # directories that the normal WSL user cannot unlink. Use the Windows filesystem operation as
  # a fallback; this does not change the fetched contents or the pinned-source contract.
  if command -v cmd.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
    cmd.exe /c rd /s /q "$(wslpath -w "$VENDOR/source")" >/dev/null 2>&1 || true
    cmd.exe /c rd /s /q "$(wslpath -w "$VENDOR/dobby")" >/dev/null 2>&1 || true
  else
    echo "Unable to clean vendor extraction directories" >&2
    exit 1
  fi
fi
if ! mkdir -p "$VENDOR/source" "$VENDOR/dobby"; then
  if command -v cmd.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
    cmd.exe /c mkdir "$(wslpath -w "$VENDOR/source")" >/dev/null 2>&1 || true
    cmd.exe /c mkdir "$(wslpath -w "$VENDOR/dobby")" >/dev/null 2>&1 || true
  else
    echo "Unable to create vendor extraction directories" >&2
    exit 1
  fi
fi
tar -xzf "$archive" --strip-components=1 -C "$VENDOR/source"
tar -xzf "$dobby_archive" --strip-components=1 -C "$VENDOR/dobby"

# Never import upstream packaged artifacts or signing/media material.
find "$VENDOR/source" -type f \( -name '*.a' -o -name '*.so' -o -name '*.jar' -o -name '*.aar' -o -name '*.apk' -o -iname '*.png' -o -iname '*.jpg' -o -iname '*.jpeg' -o -iname '*.webp' \) -delete
rm -rf "$VENDOR/source/app" "$VENDOR/source/blackbox-gallery-stub"
# Windows fix: two inner classes in IPackageManagerProxy differ only by case
# (GetComponentEnabledSetting vs getComponentEnabledSetting); their .class files collide on
# a case-insensitive filesystem and D8 dexing fails. Both register via the @ProxyMethod
# string, not the class name, so renaming the lowercase one is safe.
pmproxy="$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IPackageManagerProxy.java"
[[ -f "$pmproxy" ]] && sed -i 's/public static class getComponentEnabledSetting extends MethodHook/public static class GetComponentEnabledSettingLegacy extends MethodHook/' "$pmproxy"

# Dobby ODR fix: logging.h defines Logger::Shared() in-header without `inline`, so every
# translation unit emits a strong definition and the shared lib fails to link with
# "duplicate symbol: Logger::Shared()". Make it inline.
logging_h="$VENDOR/dobby/external/logging/logging/logging.h"
[[ -f "$logging_h" ]] && sed -i 's/^Logger \*Logger::Shared() {/inline Logger *Logger::Shared() {/' "$logging_h"

# The fork's lifecycle callback list is dispatched after Activity.onCreate. The path-B host
# needs one callback after Activity.attach() but before the delegate initializes resources or
# calls the guest onCreate, otherwise applyOverrideConfiguration() is already too late. Keep
# this patch here because Bcore is fetched into the ignored vendor tree on every clean build.
python3 - "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/AppInstrumentation.java" \
         "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/BaseInstrumentationDelegate.java" <<'PY'
from pathlib import Path
import sys

app = Path(sys.argv[1])
base = Path(sys.argv[2])

text = app.read_text()
if "notifyBeforeMainActivityOnCreate(activity);" not in text:
    old = """    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
    }"""
    new = """    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        notifyBeforeMainActivityOnCreate(activity);
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle, persistentState);
    }"""
    text = text.replace(old, new)
    old = """    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle);
    }"""
    new = """    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        notifyBeforeMainActivityOnCreate(activity);
        checkActivity(activity);
        super.callActivityOnCreate(activity, icicle);
    }"""
    text = text.replace(old, new)
    marker = "\n    @Override\n    public void callApplicationOnCreate(Application app) {"
    helper = """
    private void notifyBeforeMainActivityOnCreate(Activity activity) {
        try {
            BlackBoxCore.get().onBeforeMainActivityOnCreate(activity);
        } catch (Throwable t) {
            Log.w(TAG, \"beforeMainActivityOnCreate callback failed\", t);
        }
    }
"""
    text = text.replace(marker, helper + marker)
    app.write_text(text)

text = base.read_text()
if "private void notifyBeforeMainActivityOnCreate" not in text:
    old = """    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        mBaseInstrumentation.callActivityOnCreate(activity, icicle);"""
    new = """    public void callActivityOnCreate(Activity activity, Bundle icicle) {
        notifyBeforeMainActivityOnCreate(activity);
        mBaseInstrumentation.callActivityOnCreate(activity, icicle);"""
    text = text.replace(old, new)
    old = """    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        mBaseInstrumentation.callActivityOnCreate(activity, icicle, persistentState);"""
    new = """    public void callActivityOnCreate(Activity activity, Bundle icicle, PersistableBundle persistentState) {
        notifyBeforeMainActivityOnCreate(activity);
        mBaseInstrumentation.callActivityOnCreate(activity, icicle, persistentState);"""
    text = text.replace(old, new)
    marker = "\n    @Override\n    public void callActivityOnDestroy(Activity activity) {"
    helper = """
    private void notifyBeforeMainActivityOnCreate(Activity activity) {
        try {
            // Activity.attach() has completed, but the delegate has not yet called the base
            // Instrumentation or guest onCreate().
            BlackBoxCore.get().onBeforeMainActivityOnCreate(activity);
        } catch (Throwable t) {
            android.util.Log.w(\"BaseInstrumentationDelegate\",
                    \"beforeMainActivityOnCreate callback failed\", t);
        }
    }
"""
    text = text.replace(marker, helper + marker)
    base.write_text(text)
PY

# Make package import failures observable and non-successful. The upstream service returns a
# default-success InstallResult even when its broad catch drops an exception, which makes a
# failed system-package import look successful to the host harness.
python3 - "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/BPackageManagerService.java" \
         "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/core/system/pm/Settings.java" \
         "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IPackageManagerProxy.java" <<'PY'
from pathlib import Path
import sys

pm = Path(sys.argv[1])
settings = Path(sys.argv[2])
ipm = Path(sys.argv[3])

text = pm.read_text()
ipm_text = ipm.read_text()
start = ipm_text.find('            if ("com.android.vending".equals(packageName)) {')
if start < 0:
    raise SystemExit("Play Store fake package branch marker not found")
end = ipm_text.find('            PackageInfo packageInfo =', start)
if end < 0:
    raise SystemExit("Play Store normal package branch marker not found")
replacement = """            // Play Store must use the normal virtual package path. The former hard-coded fake
            // response omitted signing fields and used a host-independent UID, so Google apps
            // rejected it as an invalid Play Store (error 9). PackageManagerCompat supplies the
            // real host certificate for installed packages, with the parsed APK as fallback.
"""
ipm_text = ipm_text[:start] + replacement + ipm_text[end:]
ipm.write_text(ipm_text)
old = """        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
"""
new = """        } catch (Throwable t) {
            String detail = t.getClass().getName() + ": " + t.getMessage();
            Slog.e(TAG, "installPackageAsUserLocked failed file=" + file
                    + " package=" + result.packageName + " detail=" + detail);
            result.installError(result.packageName, detail);
            t.printStackTrace();
        } finally {
"""
if old not in text:
    raise SystemExit("BPackageManagerService install catch marker not found")
text = text.replace(old, new, 1)

old = """        String name = file.getName();
        if (name != null && name.toLowerCase().endsWith(\".apks\")) {
            return true;
        }
"""
new = """        String name = file.getName();
        if (name != null) {
            String lowerName = name.toLowerCase();
            if (lowerName.endsWith(\".apks\")) {
                return true;
            }
            // A normal APK may embed multiple APK-shaped assets (GMS does this for Dynamite).
            // Never reinterpret a directly supplied .apk as an APK-set bundle.
            if (lowerName.endsWith(\".apk\")) {
                return false;
            }
        }
"""
if old not in text:
    raise SystemExit("APKS detection marker not found")
text = text.replace(old, new, 1)
pm.write_text(text)

ipm_text = ipm.read_text()
old = """    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
"""
new = """    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            ApplicationInfo info = BlackBoxCore.getBPackageManager().getApplicationInfo(
                    packageName, 0, BlackBoxCore.getUserId());
            if (info != null) {
                // Return the virtual app id. Delegating this query to the host PM makes Android
                // reject Play Store/GMS checks because the caller is our host UID, not the host
                // package's real UID.
                return info.uid;
            }
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }
"""
if old not in ipm_text:
    raise SystemExit("GetPackageUid marker not found")
ipm.write_text(ipm_text.replace(old, new, 1))

text = settings.read_text()
old = """        } catch (Throwable e) {
            e.printStackTrace();
"""
new = """        } catch (Throwable e) {
            Slog.e(TAG, "bad Package " + packageName + " detail="
                    + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
"""
if old not in text:
    raise SystemExit("Settings scan catch marker not found")
text = text.replace(old, new, 1)
settings.write_text(text)
PY

# Round 8 service diagnostics are injected into the fetched vendor sources below.
python3 - "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IActivityManagerProxy.java" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
marker = '            if (resolveInfo != null || AppSystemEnv.isOpenPackage(intent.getComponent())) {'
insert = '''            if (resolveInfo != null && resolveInfo.serviceInfo != null) {
                android.content.pm.ServiceInfo target = resolveInfo.serviceInfo;
                Slog.i(TAG, "B1_SERVICE_RESOLVED method=" + method.getName() +
                        " package=" + target.packageName + " class=" + target.name +
                        " process=" + target.processName + " flags=0x" +
                        Integer.toHexString(target.flags) + " isolated=" +
                        ((target.flags & android.content.pm.ServiceInfo.FLAG_ISOLATED_PROCESS) != 0));
            } else {
                Slog.w(TAG, "B1_SERVICE_UNRESOLVED method=" + method.getName() +
                        " intent=" + intent);
            }
'''
if marker not in text:
    raise SystemExit('service resolve marker not found')
path.write_text(text.replace(marker, insert + marker, 1))
PY
python3 - "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IActivityManagerProxy.java" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
marker = '''                if (proxyIntent != null) {
                    args[2] = proxyIntent;
                    return method.invoke(who, args);
                }
'''
replacement = '''                if (proxyIntent != null) {
                    Slog.i(TAG, "B1_SERVICE_PROXY method=" + method.getName() +
                            " component=" + proxyIntent.getComponent() +
                            " original=" + intent.getComponent());
                    args[2] = proxyIntent;
                    return method.invoke(who, args);
                }
'''
if marker not in text:
    raise SystemExit('service proxy marker not found')
path.write_text(text.replace(marker, replacement, 1))
PY
python3 - "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IActivityManagerProxy.java" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
start = text.index('public static class BindIsolatedService')
end = text.index('public static class UnbindService', start)
part = text[start:end]
marker = '            args[6] = null;'
replacement = '''            Slog.i(TAG, "B1_BIND_ISOLATED_SERVICE args=" +
                    (args == null ? -1 : args.length) + " instance=" +
                    (args != null && args.length > 6 ? args[6] : null));
            args[6] = null;'''
if marker not in part:
    raise SystemExit('isolated bind marker not found')
text = text[:start] + part.replace(marker, replacement, 1) + text[end:]
path.write_text(text)
PY

# Log the service metadata again at ProxyService creation, after the proxy intent has crossed the
# virtual AMS. This distinguishes a resolved child service from a service that never instantiates.
python3 - "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/app/dispatcher/AppServiceDispatcher.java" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
marker = "        ServiceInfo serviceInfo = serviceRecord.mServiceInfo;\n\n        if (intent == null || serviceInfo == null)"
replacement = """        ServiceInfo serviceInfo = serviceRecord.mServiceInfo;

        if (serviceInfo != null) {
            android.util.Log.i(TAG, \"B1_SERVICE_CREATE package=\" + serviceInfo.packageName +
                    \" class=\" + serviceInfo.name + \" process=\" + serviceInfo.processName +
                    \" flags=0x\" + Integer.toHexString(serviceInfo.flags) + \" isolated=\" +
                    ((serviceInfo.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0));
        }
        if (intent == null || serviceInfo == null)"""
if marker not in text:
    raise SystemExit('dispatcher service marker not found')
path.write_text(text.replace(marker, replacement, 1))
PY

# Slot ownership and host-identity fixes.  Proxy service Intents can be delayed by the real
# ActivityManager.  Record the process generation that selected the proxy slot, reject a stale
# Intent before loading its guest class, and keep system-facing package/UID pairs host-consistent.
python3 - \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/proxy/record/ProxyServiceRecord.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/core/system/am/ActiveServices.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/core/system/BProcessManagerService.java" \
    "$VENDOR/source/Bcore/src/main/aidl/top/niunaijun/blackbox/core/system/am/IBActivityManagerService.aidl" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/core/system/am/BActivityManagerService.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/frameworks/BActivityManager.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/app/dispatcher/AppServiceDispatcher.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/proxy/ProxyService.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/app/BActivityThread.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/ContentProviderDelegate.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IActivityManagerProxy.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/utils/compat/ContextCompat.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/delegate/AppInstrumentation.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/utils/AttributionSourceUtils.java" \
    "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/fake/service/IAppOpsManagerProxy.java" <<'PY'
from pathlib import Path
import sys

(proxy_record, active_services, process_manager, aidl, am_service, am_client,
 dispatcher, proxy_service, activity_thread, provider_delegate, am_proxy,
 context_compat, instrumentation, attribution_utils, appops) = map(Path, sys.argv[1:])


def replace_once(path, old, new, label):
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 marker in {path}, found {count}")
    path.write_text(text.replace(old, new, 1))


# Preserve the process record Binder as a generation token. An old request for P3 must not be
# accepted by the newer occupant of P3.
replace_once(proxy_record,
"""    public int mStartId;

    public ProxyServiceRecord(Intent serviceIntent, ServiceInfo serviceInfo, IBinder token, int userId, int startId) {""",
"""    public int mStartId;
    public int mBpid = -1;
    public IBinder mProcessToken;

    public ProxyServiceRecord(Intent serviceIntent, ServiceInfo serviceInfo, IBinder token, int userId, int startId) {""",
"ProxyServiceRecord fields")
replace_once(proxy_record,
"""    public static void saveStub(Intent shadow, Intent target, ServiceInfo serviceInfo, IBinder token, int userId, int startId) {
        shadow.putExtra("_B_|_target_", target);
        shadow.putExtra("_B_|_service_info_", serviceInfo);
        shadow.putExtra("_B_|_user_id_", userId);
        shadow.putExtra("_B_|_start_id_", startId);
        BundleCompat.putBinder(shadow, "_B_|_token_", token);
    }""",
"""    public static void saveStub(Intent shadow, Intent target, ServiceInfo serviceInfo, IBinder token,
                                  int userId, int startId, int bpid, IBinder processToken) {
        shadow.putExtra("_B_|_target_", target);
        shadow.putExtra("_B_|_service_info_", serviceInfo);
        shadow.putExtra("_B_|_user_id_", userId);
        shadow.putExtra("_B_|_start_id_", startId);
        shadow.putExtra("_B_|_bpid_", bpid);
        BundleCompat.putBinder(shadow, "_B_|_token_", token);
        BundleCompat.putBinder(shadow, "_B_|_process_token_", processToken);
    }""",
"ProxyServiceRecord saveStub")
replace_once(proxy_record,
"""        int startId = intent.getIntExtra("_B_|_start_id_", 0);
        IBinder token = BundleCompat.getBinder(intent, "_B_|_token_");
        return new ProxyServiceRecord(target, serviceInfo, token, userId, startId);""",
"""        int startId = intent.getIntExtra("_B_|_start_id_", 0);
        int bpid = intent.getIntExtra("_B_|_bpid_", -1);
        IBinder token = BundleCompat.getBinder(intent, "_B_|_token_");
        ProxyServiceRecord record = new ProxyServiceRecord(target, serviceInfo, token, userId, startId);
        record.mBpid = bpid;
        record.mProcessToken = BundleCompat.getBinder(intent, "_B_|_process_token_");
        return record;""",
"ProxyServiceRecord create")

replace_once(active_services,
"""        ProxyServiceRecord.saveStub(stub, targetIntent, serviceInfo, runningServiceRecord, processRecord.userId, runningServiceRecord.mStartId.get());""",
"""        ProxyServiceRecord.saveStub(stub, targetIntent, serviceInfo, runningServiceRecord,
                processRecord.userId, runningServiceRecord.mStartId.get(), processRecord.bpid, processRecord);""",
"ActiveServices owner metadata")

# Make allocation and death cleanup identity-safe. The old callback for an earlier occupant can
# arrive after a slot/PID has been reused; it must not remove or kill the current record.
replace_once(process_manager,
"""            if (bpid == -1) {
                app = bProcess.get(processName);
                if (app != null) {
                    if (app.initLock != null) {
                        app.initLock.block();
                    }
                    if (app.bActivityThread != null) {
                        return app;
                    }
                }
                bpid = getUsingBPidL();
                Slog.d(TAG, "init bUid = " + buid + ", bPid = " + bpid);
            }
            if (bpid == -1) {
                throw new RuntimeException("No processes available");
            }""",
"""            if (bpid == -1) {
                app = bProcess.get(processName);
                if (app != null) {
                    boolean alive = app.bActivityThread != null
                            && app.bActivityThread.asBinder().isBinderAlive();
                    if (alive) {
                        if (app.initLock != null) {
                            app.initLock.block();
                        }
                        if (app.bActivityThread != null
                                && app.bActivityThread.asBinder().isBinderAlive()) {
                            return app;
                        }
                    }
                    Slog.w(TAG, "Removing dead process record package=" + packageName
                            + " process=" + processName + " bpid=" + app.bpid);
                    removeRecordLocked(app, true);
                }
                bpid = getUsingBPidL();
                Slog.d(TAG, "init bUid = " + buid + ", bPid = " + bpid);
            } else {
                ProcessRecord owner = findProcessByBpidLocked(bpid);
                if (owner != null && (!packageName.equals(owner.getPackageName())
                        || !processName.equals(owner.processName) || userId != owner.userId)) {
                    Slog.e(TAG, "Rejecting slot reuse bpid=" + bpid + " requested="
                            + packageName + "/" + processName + " owner="
                            + owner.getPackageName() + "/" + owner.processName);
                    return null;
                }
            }
            if (bpid == -1) {
                throw new RuntimeException("No processes available");
            }""",
"BProcessManager allocation")
replace_once(process_manager,
"""        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Set<Integer> usingPs = new HashSet<>();
        for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
            int i = parseBPid(runningAppProcess.processName);
            usingPs.add(i);
        }
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {""",
"""        List<ActivityManager.RunningAppProcessInfo> runningAppProcesses = manager.getRunningAppProcesses();
        Set<Integer> usingPs = new HashSet<>();
        if (runningAppProcesses != null) {
            for (ActivityManager.RunningAppProcessInfo runningAppProcess : runningAppProcesses) {
                int i = parseBPid(runningAppProcess.processName);
                if (i >= 0) {
                    usingPs.add(i);
                }
            }
        }
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.bpid >= 0) {
                    usingPs.add(processRecord.bpid);
                }
            }
        }
        for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {""",
"BProcessManager slot scan")
replace_once(process_manager,
"""    public void onProcessDie(ProcessRecord record) {
        synchronized (mProcessLock) {
            record.kill();
            Map<String, ProcessRecord> process = mProcessMap.get(record.buid);
            if (process != null) {
                process.remove(record.processName);
                if (process.isEmpty()) {
                    mProcessMap.remove(record.buid);
                }
            }
            mPidsSelfLocked.remove(record);

            removeProc(record);
            BNotificationManagerService.get().deletePackageNotification(record.getPackageName(), record.userId);
        }
    }""",
"""    public void onProcessDie(ProcessRecord record) {
        synchronized (mProcessLock) {
            Map<String, ProcessRecord> process = mProcessMap.get(record.buid);
            boolean current = process != null && process.get(record.processName) == record;
            if (!current) {
                // A late death callback from an old generation must not kill or clean the new owner.
                mPidsSelfLocked.remove(record);
                Slog.w(TAG, "Ignoring stale death callback package=" + record.getPackageName()
                        + " process=" + record.processName + " bpid=" + record.bpid);
                return;
            }
            process.remove(record.processName);
            if (process.isEmpty()) {
                mProcessMap.remove(record.buid);
            }
            mPidsSelfLocked.remove(record);
            removeProc(record);
            BNotificationManagerService.get().deletePackageNotification(record.getPackageName(), record.userId);
        }
    }""",
"BProcessManager death cleanup")
replace_once(process_manager,
"""    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    private static String getProcessName""",
"""    public ProcessRecord findProcessByPid(int pid) {
        synchronized (mPidsSelfLocked) {
            for (ProcessRecord processRecord : mPidsSelfLocked) {
                if (processRecord.pid == pid)
                    return processRecord;
            }
            return null;
        }
    }

    private ProcessRecord findProcessByBpidLocked(int bpid) {
        for (ProcessRecord processRecord : mPidsSelfLocked) {
            if (processRecord.bpid == bpid) {
                return processRecord;
            }
        }
        return null;
    }

    public ProcessRecord findProcessByBpid(int bpid) {
        synchronized (mProcessLock) {
            synchronized (mPidsSelfLocked) {
                return findProcessByBpidLocked(bpid);
            }
        }
    }

    private void removeRecordLocked(ProcessRecord record, boolean removeProcDir) {
        Map<String, ProcessRecord> process = mProcessMap.get(record.buid);
        if (process != null && process.get(record.processName) == record) {
            process.remove(record.processName);
            if (process.isEmpty()) {
                mProcessMap.remove(record.buid);
            }
        }
        mPidsSelfLocked.remove(record);
        if (removeProcDir) {
            removeProc(record);
        }
    }

    private static String getProcessName""",
 "BProcessManager lookup helpers")

# Add a fail-closed server check. The check runs in the engine service process and compares the
# calling host process, slot, guest package/process, user, and generation Binder token.
replace_once(aidl,
"""    void unbindService(in IBinder binder, int userId);

    void stopServiceToken""",
"""    void unbindService(in IBinder binder, int userId);
    boolean isProxyProcessOwner(in Intent proxyIntent);

    void stopServiceToken""",
"Activity manager owner AIDL")
replace_once(am_service,
"""import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.entity.am.PendingResultData;""",
"""import top.niunaijun.blackbox.entity.UnbindRecord;
import top.niunaijun.blackbox.entity.am.PendingResultData;
import top.niunaijun.blackbox.proxy.record.ProxyServiceRecord;""",
"Activity manager owner import")
replace_once(am_service,
"""    @Override
    public void unbindService(IBinder binder, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.unbindService(binder, userId);
        }
    }

    @Override
    public void stopServiceToken""",
"""    @Override
    public void unbindService(IBinder binder, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.unbindService(binder, userId);
        }
    }

    @Override
    public boolean isProxyProcessOwner(Intent proxyIntent) {
        if (proxyIntent == null) {
            return false;
        }
        ProxyServiceRecord record = ProxyServiceRecord.create(proxyIntent);
        if (record.mBpid < 0 || record.mProcessToken == null || record.mServiceInfo == null) {
            Slog.w(TAG, "Rejecting proxy request with incomplete owner metadata");
            return false;
        }
        ProcessRecord current = BProcessManagerService.get().findProcessByPid(Binder.getCallingPid());
        boolean owner = current != null
                && current.bpid == record.mBpid
                && current.userId == record.mUserId
                && current.getPackageName().equals(record.mServiceInfo.packageName)
                && current.processName.equals(record.mServiceInfo.processName)
                && record.mProcessToken.equals(current)
                && current.bActivityThread != null
                && current.bActivityThread.asBinder().isBinderAlive();
        if (!owner) {
            Slog.w(TAG, "Rejecting stale proxy request bpid=" + record.mBpid
                    + " package=" + record.mServiceInfo.packageName
                    + " process=" + record.mServiceInfo.processName
                    + " callerPid=" + Binder.getCallingPid());
        }
        return owner;
    }

    @Override
    public void stopServiceToken""",
"Activity manager owner check")
replace_once(am_service,
"""    public void onStartCommand(Intent intent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onStartCommand(intent, userId);
        }
    }""",
"""    public void onStartCommand(Intent intent, int userId) throws RemoteException {
        if (!isProxyProcessOwner(intent)) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onStartCommand(intent, userId);
        }
    }""",
"Activity manager start callback guard")
replace_once(am_service,
"""    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.onServiceUnbind(proxyIntent, userId);
        }
    }""",
"""    public UnbindRecord onServiceUnbind(Intent proxyIntent, int userId) throws RemoteException {
        if (!isProxyProcessOwner(proxyIntent)) {
            return null;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            return userSpace.mActiveServices.onServiceUnbind(proxyIntent, userId);
        }
    }""",
"Activity manager unbind callback guard")
replace_once(am_service,
"""    public void onServiceDestroy(Intent proxyIntent, int userId) throws RemoteException {
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onServiceDestroy(proxyIntent, userId);
        }
    }""",
"""    public void onServiceDestroy(Intent proxyIntent, int userId) throws RemoteException {
        if (!isProxyProcessOwner(proxyIntent)) {
            return;
        }
        UserSpace userSpace = getOrCreateSpaceLocked(userId);
        synchronized (userSpace.mActiveServices) {
            userSpace.mActiveServices.onServiceDestroy(proxyIntent, userId);
        }
    }""",
"Activity manager destroy callback guard")

replace_once(am_client,
"""    public void unbindService(IBinder binder, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.unbindService(binder, userId);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public void stopServiceToken""",
"""    public void unbindService(IBinder binder, int userId) {
        try {
            IBActivityManagerService service = getService();
            if (service != null) {
                service.unbindService(binder, userId);
            }
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    public boolean isProxyProcessOwner(Intent proxyIntent) {
        try {
            IBActivityManagerService service = getService();
            return service != null && service.isProxyProcessOwner(proxyIntent);
        } catch (RemoteException e) {
            Slog.w(TAG, "Owner check failed", e);
            return false;
        }
    }

    public void stopServiceToken""",
"Activity manager owner client")

# Validate before getOrCreateService(). This is deliberately repeated at the dispatcher and
# ProxyService entry points so no stale request reaches the guest class loader.
replace_once(dispatcher,
"""import android.os.IBinder;

import java.util.HashMap;""",
"""import android.os.IBinder;
import android.util.Log;

import java.util.HashMap;""",
"AppServiceDispatcher import")
replace_once(dispatcher,
"""    public static AppServiceDispatcher get() {
        return sServiceDispatcher;
    }

    private final Handler""",
"""    public static AppServiceDispatcher get() {
        return sServiceDispatcher;
    }

    public boolean isProxyServiceOwner(Intent proxyIntent) {
        if (proxyIntent == null) {
            return false;
        }
        try {
            boolean owner = BlackBoxCore.getBActivityManager().isProxyProcessOwner(proxyIntent);
            if (!owner) {
                Log.w(TAG, "Ignoring stale proxy service request");
            }
            return owner;
        } catch (Throwable t) {
            Log.w(TAG, "Unable to validate proxy service request", t);
            return false;
        }
    }

    private final Handler""",
"AppServiceDispatcher owner helper")
replace_once(dispatcher,
"""    public IBinder onBind(Intent proxyIntent) {
        ProxyServiceRecord serviceRecord = ProxyServiceRecord.create(proxyIntent);""",
"""    public IBinder onBind(Intent proxyIntent) {
        if (!isProxyServiceOwner(proxyIntent)) {
            return null;
        }
        ProxyServiceRecord serviceRecord = ProxyServiceRecord.create(proxyIntent);""",
"AppServiceDispatcher bind guard")
replace_once(dispatcher,
"""    public int onStartCommand(Intent proxyIntent, int flags, int startId) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);""",
"""    public int onStartCommand(Intent proxyIntent, int flags, int startId) {
        if (!isProxyServiceOwner(proxyIntent)) {
            return START_NOT_STICKY;
        }
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);""",
"AppServiceDispatcher start guard")
replace_once(dispatcher,
"""    public boolean onUnbind(Intent proxyIntent) {
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);""",
"""    public boolean onUnbind(Intent proxyIntent) {
        if (!isProxyServiceOwner(proxyIntent)) {
            return false;
        }
        ProxyServiceRecord stubRecord = ProxyServiceRecord.create(proxyIntent);""",
"AppServiceDispatcher unbind guard")

replace_once(proxy_service,
"""    public IBinder onBind(Intent intent) {
        return AppServiceDispatcher.get().onBind(intent);
    }""",
"""    public IBinder onBind(Intent intent) {
        if (!AppServiceDispatcher.get().isProxyServiceOwner(intent)) {
            return null;
        }
        return AppServiceDispatcher.get().onBind(intent);
    }""",
"ProxyService bind guard")
replace_once(proxy_service,
"""    public int onStartCommand(Intent intent, int flags, int startId) {
        AppServiceDispatcher.get().onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }""",
"""    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!AppServiceDispatcher.get().isProxyServiceOwner(intent)) {
            return START_NOT_STICKY;
        }
        AppServiceDispatcher.get().onStartCommand(intent, flags, startId);
        return START_NOT_STICKY;
    }""",
"ProxyService start guard")
replace_once(proxy_service,
"""    public boolean onUnbind(Intent intent) {
        AppServiceDispatcher.get().onUnbind(intent);
        return false;
    }""",
"""    public boolean onUnbind(Intent intent) {
        if (!AppServiceDispatcher.get().isProxyServiceOwner(intent)) {
            return false;
        }
        AppServiceDispatcher.get().onUnbind(intent);
        return false;
    }""",
"ProxyService unbind guard")
 
# Defense in depth inside the guest process: even if a new caller bypasses the proxy dispatcher,
# never instantiate a service/job with the class loader of a different package or slot owner.
replace_once(activity_thread,
"""    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader""",
"""    private boolean matchesBoundProcess(String packageName, String processName) {
        if (mAppConfig == null) {
            return true;
        }
        return TextUtils.equals(mAppConfig.packageName, packageName)
                && TextUtils.equals(mAppConfig.processName, processName)
                && mAppConfig.userId == BActivityThread.getUserId()
                && mAppConfig.bpid == BActivityThread.getAppPid();
    }

    public Service createService(ServiceInfo serviceInfo, IBinder token) {
        if (serviceInfo == null || (mAppConfig != null
                && !matchesBoundProcess(serviceInfo.packageName, serviceInfo.processName))) {
            Slog.w(TAG, "Rejecting service for another process owner: "
                    + (serviceInfo == null ? null : serviceInfo.packageName));
            return null;
        }
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        if (!matchesBoundProcess(serviceInfo.packageName, serviceInfo.processName)) {
            Slog.w(TAG, "Rejecting service after bind for another process owner: " + serviceInfo.packageName);
            return null;
        }
        ClassLoader classLoader""",
"BActivityThread service owner guard")
replace_once(activity_thread,
"""    public JobService createJobService(ServiceInfo serviceInfo) {
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        ClassLoader classLoader""",
"""    public JobService createJobService(ServiceInfo serviceInfo) {
        if (serviceInfo == null || (mAppConfig != null
                && !matchesBoundProcess(serviceInfo.packageName, serviceInfo.processName))) {
            Slog.w(TAG, "Rejecting job for another process owner: "
                    + (serviceInfo == null ? null : serviceInfo.packageName));
            return null;
        }
        if (!BActivityThread.currentActivityThread().isInit()) {
            BActivityThread.currentActivityThread().bindApplication(serviceInfo.packageName, serviceInfo.processName);
        }
        if (!matchesBoundProcess(serviceInfo.packageName, serviceInfo.processName)) {
            Slog.w(TAG, "Rejecting job after bind for another process owner: " + serviceInfo.packageName);
            return null;
        }
        ClassLoader classLoader""",
"BActivityThread job owner guard")
replace_once(activity_thread,
"""            if (this.mAppConfig != null && !this.mAppConfig.packageName.equals(appConfig.packageName)) {
                
                throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
            }""",
"""            if (this.mAppConfig != null && (!this.mAppConfig.packageName.equals(appConfig.packageName)
                    || !TextUtils.equals(this.mAppConfig.processName, appConfig.processName)
                    || this.mAppConfig.userId != appConfig.userId
                    || this.mAppConfig.bpid != appConfig.bpid)) {
                throw new RuntimeException("reject init process: " + appConfig.packageName + "/"
                        + appConfig.processName + ", this process is : " + this.mAppConfig.packageName
                        + "/" + this.mAppConfig.processName);
            }""",
"BActivityThread init owner guard")
replace_once(activity_thread,
"""    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        try {""",
"""    public synchronized void handleBindApplication(String packageName, String processName) {
        if (isInit())
            return;
        if (mAppConfig != null && !matchesBoundProcess(packageName, processName)) {
            throw new RuntimeException("reject bind application for another process owner: "
                    + packageName + "/" + processName);
        }
        try {""",
"BActivityThread bind owner guard")

# Host providers must see the host package and host UID together. The previous wrapper passed a
# guest package while the Binder call physically ran under our host UID, which is rejected by
# Android's package/AppOps checks (the observed Play Store crash).
replace_once(provider_delegate,
"""    private static String resolveProviderCallerPackage(String auth) {
        if ("settings".equals(auth)) {
            return BlackBoxCore.getHostPkg();
        }
        return resolveCallerPackage();
    }

    private static IInterface wrapProvider(IInterface provider, String auth) {
        String callerPackage = resolveProviderCallerPackage(auth);
        switch (auth) {""",
"""    private static String resolveProviderCallerPackage(String auth, boolean hostProvider) {
        if (hostProvider || "settings".equals(auth)) {
            return BlackBoxCore.getHostPkg();
        }
        return resolveCallerPackage();
    }

    private static IInterface wrapProvider(IInterface provider, String auth, boolean hostProvider) {
        String callerPackage = resolveProviderCallerPackage(auth, hostProvider);
        switch (auth) {""",
"ContentProviderDelegate host identity")
replace_once(provider_delegate,
"""    public static void update(Object holder, String auth) {
        IInterface iInterface;
        if (BuildCompat.isOreo()) {
            iInterface = BRContentProviderHolderOreo.get(holder).provider();
        } else {
            iInterface = BRIActivityManagerContentProviderHolder.get(holder).provider();
        }

        if (iInterface instanceof Proxy)
            return;
        IInterface bContentProvider = wrapProvider(iInterface, auth);
        if (BuildCompat.isOreo()) {
            BRContentProviderHolderOreo.get(holder)._set_provider(bContentProvider);
        } else {
            BRIActivityManagerContentProviderHolder.get(holder)._set_provider(bContentProvider);
        }
    }""",
"""    public static void update(Object holder, String auth) {
        update(holder, auth, false);
    }

    public static void update(Object holder, String auth, boolean hostProvider) {
        IInterface iInterface;
        if (BuildCompat.isOreo()) {
            iInterface = BRContentProviderHolderOreo.get(holder).provider();
        } else {
            iInterface = BRIActivityManagerContentProviderHolder.get(holder).provider();
        }

        if (iInterface instanceof Proxy)
            return;
        IInterface bContentProvider = wrapProvider(iInterface, auth, hostProvider);
        if (BuildCompat.isOreo()) {
            BRContentProviderHolderOreo.get(holder)._set_provider(bContentProvider);
        } else {
            BRIActivityManagerContentProviderHolder.get(holder)._set_provider(bContentProvider);
        }
    }""",
"ContentProviderDelegate update mode")
replace_once(provider_delegate,
"""                        wrapProvider(iInterface, providerName));""",
"""                        wrapProvider(iInterface, providerName, true));""",
"ContentProviderDelegate init host mode")

am_proxy_text = am_proxy.read_text()
if am_proxy_text.count("ContentProviderDelegate.update(content, (String) auth);") != 2:
    raise SystemExit("IActivityManagerProxy provider update markers changed")
am_proxy.write_text(am_proxy_text.replace(
    "ContentProviderDelegate.update(content, (String) auth);",
    "ContentProviderDelegate.update(content, (String) auth, true);"))

replace_once(context_compat,
"""            BRContextImpl.get(context)._set_mBasePackageName(pkg);
            BRContextImplKitkat.get(context)._set_mOpPackageName(pkg);""",
"""            BRContextImpl.get(context)._set_mBasePackageName(pkg);
            // Context.getPackageName() remains virtual, but system-facing operation identity must
            // be a package belonging to the real host UID.
            BRContextImplKitkat.get(context)._set_mOpPackageName(hostPkg);""",
"ContextCompat operation package")
replace_once(context_compat,
"""                BRContentResolver.get(context.getContentResolver())._set_mPackageName(pkg);""",
"""                BRContentResolver.get(context.getContentResolver())._set_mPackageName(hostPkg);""",
"ContextCompat resolver package")
replace_once(context_compat,
"""                    fixAttributionSourceState(BRContextImpl.get(context).getAttributionSource(), pkg, uid);""",
"""                    fixAttributionSourceState(BRContextImpl.get(context).getAttributionSource(),
                            hostPkg, BlackBoxCore.getHostUid());""",
"ContextCompat attribution identity")
replace_once(instrumentation,
"""            BRContextImplKitkat.get(context)._set_mOpPackageName(pkg);""",
"""            BRContextImplKitkat.get(context)._set_mOpPackageName(BlackBoxCore.getHostPkg());""",
"AppInstrumentation operation package")
replace_once(attribution_utils,
"""    private static String getAttributionPackageName() {
        try {
            String appPackageName = BActivityThread.getAppPackageName();
            if (appPackageName != null && !appPackageName.isEmpty()) {
                return appPackageName;
            }
        } catch (Throwable ignored) {
        }
        return BlackBoxCore.getHostPkg();
    }""",
"""    private static String getAttributionPackageName() {
        // AttributionSource is consumed by the real system services, so its package must belong
        // to the real UID. Guest package names remain virtual in ApplicationInfo/Context APIs.
        return BlackBoxCore.getHostPkg();
    }""",
"AttributionSource host identity")

# AppOps has many framework signatures. Normalize only a current guest package paired with the
# host/virtual UID; do not rewrite arbitrary package-query arguments.
replace_once(appops,
"""import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;""",
"""import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;""",
"AppOps owner import")
replace_once(appops,
"""        if (methodName.startsWith("check") || 
            methodName.startsWith("note") || 
            methodName.startsWith("start")) {
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName + ", allowing operation");""",
"""        if (methodName.startsWith("check") || 
            methodName.startsWith("note") || 
            methodName.startsWith("start")) {
            normalizeCurrentCallerPackage(args);
            Slog.d(TAG, "AppOps invoke: Bypassing system for " + methodName + ", allowing operation");""",
"AppOps caller normalization")
replace_once(appops,
"""    @Override
    public boolean isBadEnv() {""",
"""    private static void normalizeCurrentCallerPackage(Object[] args) {
        if (args == null) {
            return;
        }
        String guestPackage = BActivityThread.getAppPackageName();
        String hostPackage = BlackBoxCore.getHostPkg();
        if (guestPackage == null || hostPackage == null || guestPackage.equals(hostPackage)) {
            return;
        }
        boolean hasCallerUid = false;
        int hostUid = BlackBoxCore.getHostUid();
        int virtualUid = BActivityThread.getUid();
        int bUid = BActivityThread.getBUid();
        for (Object arg : args) {
            if (arg instanceof Integer) {
                int value = (Integer) arg;
                if (value == hostUid || value == virtualUid || value == bUid) {
                    hasCallerUid = true;
                    break;
                }
            }
        }
        if (!hasCallerUid) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            if (guestPackage.equals(args[i])) {
                args[i] = hostPackage;
            }
        }
    }

    @Override
    public boolean isBadEnv() {""",
"AppOps caller helper")

PY

# B-061 (Phase 7): the engine daemon's foreground notification is ours, not the engine fork's
# brand text, and it is only ever shown while a hidden Android app is alive (the app starts and
# stops DaemonService itself; isEnableDaemonService() = false at app start).
python3 - "$VENDOR/source/Bcore/src/main/java/top/niunaijun/blackbox/core/system/DaemonService.java" <<'PY'
from pathlib import Path
import sys

daemon = Path(sys.argv[1])
text = daemon.read_text()


def replace_once(old, new, label):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected 1 marker, found {count}")
    text = text.replace(old, new, 1)


replace_once(
    '    private static final String CHANNEL_NAME = "BlackBox Daemon Service";\n'
    '    private static final String CHANNEL_DESCRIPTION = "Keeps BlackBox core services running";',
    '    private static final String CHANNEL_NAME = "Android app engine";\n'
    '    private static final String CHANNEL_DESCRIPTION = "Shown only while a hidden Android app is running";',
    "DaemonService channel text")
replace_once(
    '                .setContentTitle("BlackBox Core")\n'
    '                .setContentText("Core services are running")',
    '                .setContentTitle("Android app engine")\n'
    '                .setContentText("A hidden app is running")',
    "DaemonService notification text")
daemon.write_text(text)
PY
if ! printf '%s\n' "Fetched $REV and Dobby $DOBBY_REV; prebuilt artifacts excluded." > "$VENDOR/MANIFEST"; then
  if command -v cmd.exe >/dev/null 2>&1 && command -v wslpath >/dev/null 2>&1; then
    cmd.exe /c "echo Fetched $REV and Dobby $DOBBY_REV; prebuilt artifacts excluded. > $(wslpath -w \"$VENDOR/MANIFEST\")" >/dev/null 2>&1 || true
  else
    echo "Unable to write vendor manifest" >&2
    exit 1
  fi
fi
