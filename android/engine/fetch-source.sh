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

rm -rf "$VENDOR/source" "$VENDOR/dobby"
mkdir "$VENDOR/source" "$VENDOR/dobby"
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

# Gradle/CMake read the ignored vendor tree; no generated sources enter tracked src/.
printf '%s\n' "Fetched $REV and Dobby $DOBBY_REV; prebuilt artifacts excluded." > "$VENDOR/MANIFEST"
