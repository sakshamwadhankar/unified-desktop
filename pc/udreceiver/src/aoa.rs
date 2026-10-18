//! AOA (Android Open Accessory) switch over WinUSB (item 8), Windows-only.
//!
//! The PC is the USB host: it opens the phone's USB device interface that WinUSB
//! accepts (the Google ADB driver is WinUSB-based; vendor control transfers on any
//! interface of the composite device go to endpoint 0, so the ADB interface works),
//! probes GET_PROTOCOL (control 51), sends the accessory strings with SEND_STRING (52;
//! index 0 = manufacturer, 1 = model - must match res/xml/usb_accessory_filter.xml),
//! then START (53); the phone re-enumerates as an accessory (VID 18D1, PID 2D00/2D01).
//!
//! Scope (item 8 go/no-go): list / probe / start. The accessory DATA path (open the
//! 18D1 accessory, bulk pump, TLS) is not implemented yet; the phone side now starts
//! the link service and adopts the accessory FD (manager 21:55), so what remains is
//! the transport, not the attach path.

use windows::core::PCWSTR;
use windows::Win32::Devices::DeviceAndDriverInstallation::{
    SetupDiDestroyDeviceInfoList, SetupDiEnumDeviceInterfaces, SetupDiGetClassDevsW,
    SetupDiGetDeviceInterfaceDetailW, DIGCF_DEVICEINTERFACE, DIGCF_PRESENT,
    SP_DEVICE_INTERFACE_DATA, SP_DEVICE_INTERFACE_DETAIL_DATA_W,
};
use windows::Win32::Devices::Usb::{
    WinUsb_ControlTransfer, WinUsb_Free, WinUsb_Initialize, GUID_DEVINTERFACE_USB_DEVICE,
    WINUSB_INTERFACE_HANDLE, WINUSB_SETUP_PACKET,
};
use windows::Win32::Foundation::{CloseHandle, HANDLE};
use windows::Win32::Storage::FileSystem::{
    CreateFileW, FILE_ATTRIBUTE_NORMAL, FILE_SHARE_READ, FILE_SHARE_WRITE, OPEN_EXISTING,
};

/// AOA control request codes.
pub const REQ_GET_PROTOCOL: u8 = 51;
pub const REQ_SEND_STRING: u8 = 52;
pub const REQ_START: u8 = 53;

/// SEND_STRING strings; 0/1 must match res/xml/usb_accessory_filter.xml.
pub const STRINGS: [(u16, &str); 3] = [
    (0, "unified-desktop"),                 // manufacturer
    (1, "desktop"),                         // model
    (2, "unified-desktop phone as the PC"), // description
];

pub fn setup_packet(
    request_type: u8,
    request: u8,
    value: u16,
    index: u16,
    length: u16,
) -> WINUSB_SETUP_PACKET {
    WINUSB_SETUP_PACKET {
        RequestType: request_type,
        Request: request,
        Value: value,
        Index: index,
        Length: length,
    }
}

/// The SEND_STRING requests as (string index, UTF-8 bytes incl. NUL) in order.
pub fn send_string_requests() -> Vec<(u16, Vec<u8>)> {
    STRINGS
        .iter()
        .map(|(i, s)| {
            let mut b = s.as_bytes().to_vec();
            b.push(0);
            (*i, b)
        })
        .collect()
}

/// Optional `--vid <hex>` filter against the device path (`vid_18d1`).
pub fn vid_matches(path: &str, vid_hex: &str) -> bool {
    let v = vid_hex.to_ascii_lowercase();
    let v = v.trim_start_matches("0x");
    if v.is_empty() {
        return true;
    }
    path.to_ascii_lowercase().contains(&format!("vid_{v}"))
}

struct UsbDev {
    path: String,
    handle: HANDLE,
    winusb: WINUSB_INTERFACE_HANDLE,
}

impl Drop for UsbDev {
    fn drop(&mut self) {
        unsafe {
            let _ = WinUsb_Free(self.winusb);
            let _ = CloseHandle(self.handle);
        }
    }
}

fn open_winusb(path: &str) -> Option<UsbDev> {
    let wide: Vec<u16> = path.encode_utf16().chain(std::iter::once(0)).collect();
    unsafe {
        let handle = CreateFileW(
            PCWSTR(wide.as_ptr()),
            0x8000_0000 | 0x4000_0000, // GENERIC_READ | GENERIC_WRITE
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            None,
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL,
            None,
        )
        .ok()?;
        let mut winusb = WINUSB_INTERFACE_HANDLE(std::ptr::null_mut());
        if WinUsb_Initialize(handle, &mut winusb).is_err() {
            let _ = CloseHandle(handle);
            return None;
        }
        Some(UsbDev {
            path: path.to_string(),
            handle,
            winusb,
        })
    }
}

impl UsbDev {
    /// GET_PROTOCOL (control 51): device-to-host vendor read of 2 bytes, little-endian.
    fn get_protocol(&self) -> Result<u16, String> {
        let mut resp = [0u8; 2];
        let mut done = 0u32;
        unsafe {
            WinUsb_ControlTransfer(
                self.winusb,
                setup_packet(0xC0, REQ_GET_PROTOCOL, 0, 0, 2),
                Some(&mut resp[..]),
                Some(&mut done),
                None,
            )
            .map_err(|e| format!("WinUsb_ControlTransfer(51): {e}"))?;
        }
        if done < 2 {
            return Err(format!("short GET_PROTOCOL read ({done} bytes)"));
        }
        Ok(u16::from_le_bytes(resp))
    }

    fn send_string(&self, index: u16, data: &[u8]) -> Result<(), String> {
        let mut buf = data.to_vec();
        let mut done = 0u32;
        unsafe {
            WinUsb_ControlTransfer(
                self.winusb,
                setup_packet(0x40, REQ_SEND_STRING, index, 0, data.len() as u16),
                Some(&mut buf[..]),
                Some(&mut done),
                None,
            )
            .map_err(|e| format!("WinUsb_ControlTransfer(52 idx {index}): {e}"))
        }
    }

    fn start(&self) -> Result<(), String> {
        unsafe {
            WinUsb_ControlTransfer(
                self.winusb,
                setup_packet(0x40, REQ_START, 0, 0, 0),
                None,
                None,
                None,
            )
            .map_err(|e| format!("WinUsb_ControlTransfer(53): {e}"))
        }
    }
}

fn enumerate_usb_interfaces() -> Vec<String> {
    let mut out = Vec::new();
    unsafe {
        let Ok(hdev) = SetupDiGetClassDevsW(
            Some(&GUID_DEVINTERFACE_USB_DEVICE),
            PCWSTR::null(),
            None,
            DIGCF_PRESENT | DIGCF_DEVICEINTERFACE,
        ) else {
            return out;
        };
        let mut index = 0u32;
        loop {
            let mut ifd: SP_DEVICE_INTERFACE_DATA = std::mem::zeroed();
            ifd.cbSize = std::mem::size_of::<SP_DEVICE_INTERFACE_DATA>() as u32;
            if SetupDiEnumDeviceInterfaces(hdev, None, &GUID_DEVINTERFACE_USB_DEVICE, index, &mut ifd)
                .is_err()
            {
                break;
            }
            index += 1;
            let mut buf = [0u8; 1024];
            let detail = buf.as_mut_ptr() as *mut SP_DEVICE_INTERFACE_DETAIL_DATA_W;
            (*detail).cbSize = std::mem::size_of::<SP_DEVICE_INTERFACE_DETAIL_DATA_W>() as u32;
            if SetupDiGetDeviceInterfaceDetailW(hdev, &ifd, Some(detail), buf.len() as u32, None, None)
                .is_err()
            {
                continue;
            }
            let path_ptr = buf.as_ptr().add(4) as *const u16; // DevicePath after cbSize
            let mut n = 0usize;
            while n < 500 && *path_ptr.add(n) != 0 {
                n += 1;
            }
            let path = String::from_utf16_lossy(std::slice::from_raw_parts(path_ptr, n));
            if !path.is_empty() {
                out.push(path);
            }
        }
        let _ = SetupDiDestroyDeviceInfoList(hdev);
    }
    out
}

/// Enumerate, open what WinUSB accepts, probe GET_PROTOCOL; returns AOA-capable ones.
fn find_aoa(vid_hex: &str, quiet: bool) -> Vec<(UsbDev, u16)> {
    let mut found = Vec::new();
    for path in enumerate_usb_interfaces() {
        if !vid_matches(&path, vid_hex) {
            continue;
        }
        let Some(dev) = open_winusb(&path) else {
            if !quiet {
                println!("AOA ENUM skip (WinUSB open failed): {path}");
            }
            continue;
        };
        match dev.get_protocol() {
            Ok(v @ 1..=2) => {
                println!("AOA GO: protocol={v} {path}");
                found.push((dev, v));
            }
            Ok(v) => {
                if !quiet {
                    println!("AOA ENUM not-AOA (GET_PROTOCOL={v}): {path}");
                }
            }
            Err(e) => {
                if !quiet {
                    println!("AOA ENUM probe failed ({e}): {path}");
                }
            }
        }
    }
    found
}

pub fn cmd_list(vid_hex: &str) {
    let paths = enumerate_usb_interfaces();
    println!("AOA LIST {} USB device interface(s) present", paths.len());
    for p in &paths {
        println!("AOA ENUM {p}");
    }
    let found = find_aoa(vid_hex, false);
    if found.is_empty() {
        println!("AOA none: no USB device interface answered GET_PROTOCOL (51)");
    }
}

pub fn cmd_probe(vid_hex: &str) -> bool {
    let found = find_aoa(vid_hex, false);
    if let Some((_, v)) = found.first() {
        println!("AOA PROBE GO (protocol {v}); run `aoa start` to switch the phone");
        return true;
    }
    println!("AOA PROBE NO-GO: no WinUSB-openable interface answered GET_PROTOCOL (51).");
    println!(
        "AOA driver story: Windows must bind a WinUSB-based driver to one of the phone's \
         USB interfaces; the Google ADB driver is WinUSB-based, so an adb-connected phone \
         normally qualifies. If NO-GO: use a data-capable cable, unlock the phone, and stop \
         other tools that may hold the device. Do not install or replace any USB driver \
         without the owner's explicit consent."
    );
    false
}

pub fn cmd_start(vid_hex: &str) -> bool {
    let found = find_aoa(vid_hex, true);
    let Some((dev, v)) = found.first() else {
        println!("AOA START NO-GO: no AOA-capable interface found (run `aoa probe` for details)");
        return false;
    };
    println!("AOA START using protocol {v} {}", dev.path);
    for (index, data) in send_string_requests() {
        match dev.send_string(index, &data) {
            Ok(()) => println!("AOA SEND_STRING idx={index} ok ({} bytes)", data.len()),
            Err(e) => {
                println!("AOA SEND_STRING idx={index} FAILED: {e}");
                return false;
            }
        }
    }
    match dev.start() {
        Ok(()) => {
            println!("AOA START sent: the phone re-enumerates as accessory VID_18D1 PID_2D00/2D01.");
            println!(
                "On the phone: the accessory activity appears; after Allow the link service \
                 adopts the FD (p4.log line 'ACC adopted fd=')."
            );
            true
        }
        Err(e) => {
            println!("AOA START FAILED: {e}");
            false
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn setup_packet_fields() {
        // WINUSB_SETUP_PACKET is repr(packed): read fields by value before asserting.
        let p = setup_packet(0xC0, REQ_GET_PROTOCOL, 0, 0, 2);
        assert_eq!({ p.RequestType }, 0xC0);
        assert_eq!({ p.Request }, 51);
        assert_eq!({ p.Length }, 2);
        let p = setup_packet(0x40, REQ_SEND_STRING, 1, 0, 9);
        assert_eq!({ p.Value }, 1);
        assert_eq!({ p.Length }, 9);
    }

    #[test]
    fn strings_are_the_filter_identity() {
        let reqs = send_string_requests();
        assert_eq!(reqs[0].0, 0);
        assert_eq!(reqs[0].1, b"unified-desktop\0");
        assert_eq!(reqs[1].0, 1);
        assert_eq!(reqs[1].1, b"desktop\0");
        assert!(reqs.iter().all(|(_, b)| b.last() == Some(&0)));
    }

    #[test]
    fn vid_filter_matches_paths() {
        let p = r"\\?\usb#vid_18d1&pid_4ee7#7&1234&0&2#{a5dcbf10-6530-11d2-901f-00c04fb951ed}";
        assert!(vid_matches(p, "18d1"));
        assert!(vid_matches(p, "0x18D1"));
        assert!(!vid_matches(p, "046d"));
        assert!(vid_matches(p, ""));
    }
}
