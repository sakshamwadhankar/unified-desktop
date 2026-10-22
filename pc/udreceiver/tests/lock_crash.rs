//! B-050 regression: the release profile uses `panic = "abort"`, so a crashed receiver never
//! runs destructors. The single-session mutex must be released by the OS and the dirty lock
//! journal must still be recoverable by the next `lock-recover`.
#![cfg(windows)]

use std::fs;
use std::process::Command;

#[test]
fn abort_while_holding_the_session_leaves_a_recoverable_journal() {
    let stamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap()
        .as_nanos();
    let dir = std::env::temp_dir().join(format!(
        "udreceiver-abort-test-{}-{stamp}",
        std::process::id()
    ));
    fs::create_dir_all(&dir).unwrap();
    let exe = env!("CARGO_BIN_EXE_udreceiver");

    let crashed = Command::new(exe)
        .arg("lock-crash-test")
        .env("UD_LOCK_DIR", &dir)
        .output()
        .expect("spawn lock-crash-test");
    assert!(
        !crashed.status.success(),
        "the crash helper must die, not exit cleanly"
    );
    let journal = dir.join("lock-state.txt");
    assert!(journal.exists(), "a crash must leave the undo journal behind");
    let text = fs::read_to_string(&journal).unwrap();
    assert!(
        text.contains("udreceiver-lock-crash-test"),
        "journal lost its desktop record: {text}"
    );

    let recovered = Command::new(exe)
        .arg("lock-recover")
        .env("UD_LOCK_DIR", &dir)
        .output()
        .expect("spawn lock-recover");
    assert!(
        recovered.status.success(),
        "recovery failed: stdout={} stderr={}",
        String::from_utf8_lossy(&recovered.stdout),
        String::from_utf8_lossy(&recovered.stderr)
    );
    assert!(
        !journal.exists(),
        "a successful recovery must remove the dirty journal"
    );

    let _ = fs::remove_dir_all(&dir);
}
