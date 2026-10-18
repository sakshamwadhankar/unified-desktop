// Phase 3 (ARCHITECTURE H8): diagnostic preload for code-server's node.
//
// The app starts libnode.so with "-r <this file>" before out/node/entry.js. Node's own
// module machinery then logs every native addon it is about to load together with the
// resolved (real) path, so the phase-3 log proves *which* bytes each native module came
// from: the mapped paths under files/phase3/code-server are symlinks into the APK's
// nativeLibraryDir (Phase3Data.linkNativeModules), and "underNativeLibDir=true" on every
// line is the evidence that no native code is loaded from app-writable storage.
//
// It is diagnostic only: it never redirects a load and never changes behaviour.
'use strict';
const Module = require('module');
const fs = require('fs');

const libDir = process.env.UD_LIB_DIR || '';
const orig = Module._extensions['.node'];

Module._extensions['.node'] = function (mod, filename) {
  let real = filename;
  let err = null;
  try {
    real = fs.realpathSync(filename);
  } catch (e) {
    err = e.message;
  }
  console.log('[ud-hook] dlopen request=' + filename +
    ' real=' + real +
    ' underNativeLibDir=' + (!!libDir && real.indexOf(libDir) === 0) +
    (err ? ' realpathErr=' + err : ''));
  return orig.call(this, mod, filename);
};

console.log('[ud-hook] preload node=' + process.version + ' arch=' + process.arch +
  ' abi=' + process.versions.modules + ' platform=' + process.platform +
  ' exe=' + process.execPath + ' libDir=' + libDir);

// The harness uses this to prove the preload path on its own, without code-server.
if (process.env.UD_HOOK_PROBE === '1') {
  const pty = libDir + '/libpty.so';
  const m = { exports: {} };
  try {
    process.dlopen(m, pty);
    console.log('[ud-hook] probe dlopen ' + pty + ' ok exports=' + Object.keys(m.exports).join(','));
  } catch (e) {
    console.log('[ud-hook] probe dlopen ' + pty + ' FAILED ' + e.message);
    process.exitCode = 5;
  }
}
