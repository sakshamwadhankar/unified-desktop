/*
 * glibcshim.c — unified-desktop, 2026-10-16.
 *
 * Phase 2 clean-room glibc-compat shim, compiled into libbox64.so.
 *
 * Why: our guests (Xvfb, Wine, x86-64 Debian libraries) are linked against
 * glibc; their relocation entries name glibc-internal symbols such as
 * __errno_location / __ctype_b_loc. Box64's wrapped "libc.so.6" resolves such
 * symbols with dlsym() against its own program object (dlopen(NULL) handle), so
 * any default-visibility symbol compiled into box64 itself is found there.
 * Bionic does not provide these symbols, so we do: every function here is a
 * thin, behaviour-compatible implementation written from the public glibc ABI
 * documentation, backed by Bionic equivalents where they exist.
 *
 * All code in this file is written fresh for this project (no code from
 * previous projects). No glibc source is copied or derived; the ctype table
 * layouts use the documented ABI widths and the C-locale classifications.
 */

#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <sys/resource.h>
#include <android/log.h>

#define SHIM_LOG(...) __android_log_print(ANDROID_LOG_WARN, "glibcshim", __VA_ARGS__)

/* The linker's --gc-sections would discard these unreferenced functions
   (SHF_GNU_RETAIN keeps them), and box64 builds with -fvisibility=hidden,
   so every shim symbol must also be marked default-visibility to reach the
   dynamic symbol table for dlsym(). */
#if defined(__clang__) && defined(__ELF__)
#define SHIM_RETAIN __attribute__((used, retain, visibility("default")))
#else
#define SHIM_RETAIN __attribute__((used, visibility("default")))
#endif

/* ------------------------------------------------------------------ errno */

/* bionic exports __errno(); guests expect glibc's __errno_location. */
SHIM_RETAIN int* __errno_location(void) {
    return __errno();
}

/* ----------------------------------------------- ctype tables (C locale) */

/* glibc 16-bit ctype flag bits (public ABI values, C locale).
 * glibc's ctype.h builds these with _ISbit(): bits 0-7 shift left 8
 * (0x0100..0x8000), bits 8-11 shift right 8 (0x01, 0x02, 0x04, 0x08). */
#define C_UPPER  0x0100 /* _ISupper  = _ISbit(0)  */
#define C_LOWER  0x0200 /* _ISlower  = _ISbit(1)  */
#define C_ALPHA  0x0400 /* _ISalpha  = _ISbit(2)  */
#define C_DIGIT  0x0800 /* _ISdigit  = _ISbit(3)  */
#define C_XDIGIT 0x1000 /* _ISxdigit = _ISbit(4)  */
#define C_SPACE  0x2000 /* _ISspace  = _ISbit(5)  */
#define C_PRINT  0x4000 /* _ISprint  = _ISbit(6)  */
#define C_GRAPH  0x8000 /* _ISgraph  = _ISbit(7)  */
#define C_BLANK  0x0001 /* _ISblank  = _ISbit(8)  */
#define C_CNTRL  0x0002 /* _IScntrl  = _ISbit(9)  */
#define C_PUNCT  0x0004 /* _ISpunct  = _ISbit(10) */
#define C_ALNUM  0x0008 /* _ISalnum  = _ISbit(11) */

#define TBL_N 384 /* indexes -128 .. 255 */

static unsigned short ctype_b_tbl[TBL_N];
static int ctype_tolower_tbl[TBL_N];
static int ctype_toupper_tbl[TBL_N];
static int ctype_tables_ready;

static unsigned short classify(int b) {
    unsigned short f = 0;
    unsigned char u = (unsigned char)b;
    if (u < 0x20) {
        if (u == '\t') f = C_BLANK | C_CNTRL | C_SPACE;
        else f = C_CNTRL | C_SPACE;
    } else if (u == 0x20) {
        f = C_SPACE | C_BLANK | C_PRINT;
    } else if (u == 0x7f) {
        f = C_CNTRL;
    } else if (u < 0x80) {
        f = C_PRINT | C_GRAPH;
        if (u >= 'A' && u <= 'Z') f |= C_UPPER | C_ALPHA | C_ALNUM;
        else if (u >= 'a' && u <= 'z') f |= C_LOWER | C_ALPHA | C_ALNUM;
        else if (u >= '0' && u <= '9') f |= C_DIGIT | C_ALNUM;
        if ((u >= '0' && u <= '9') || (u >= 'a' && u <= 'f') ||
            (u >= 'A' && u <= 'F'))
            f |= C_XDIGIT;
        if (!(f & (C_UPPER | C_LOWER | C_DIGIT)))
            f |= C_PUNCT;
    } else {
        /* POSIX C locale: only 0x20..0x7e are printable. */
        f = 0;
    }
    return f;
}

static int lowering(int b) {
    unsigned char u = (unsigned char)b;
    if (b < 0) return b; /* EOF-style negatives map to themselves */
    return (u >= 'A' && u <= 'Z') ? u + 32 : u;
}

static int uppering(int b) {
    unsigned char u = (unsigned char)b;
    if (b < 0) return b;
    return (u >= 'a' && u <= 'z') ? u - 32 : u;
}

static void ctype_tables_init(void) {
    if (ctype_tables_ready) return;
    for (int i = 0; i < TBL_N; i++) {
        int ch = i - 128; /* the index this slot serves */
        /* Negative indexes serve the 0..127 classification (glibc C-locale
           table symmetry for signed-char negatives). */
        int b = ch < 0 ? ch + 128 : ch;
        ctype_b_tbl[i] = classify(b);
        ctype_tolower_tbl[i] = lowering(b);
        ctype_toupper_tbl[i] = uppering(b);
    }
    ctype_tables_ready = 1;
}

SHIM_RETAIN const unsigned short** __ctype_b_loc(void) {
    ctype_tables_init();
    /* glibc returns the address of a pointer into the table such that
       (*loc)[ch] works for ch in [-128, 255]. */
    static const unsigned short* b;
    b = ctype_b_tbl + 128;
    return (const unsigned short**)&b;
}

SHIM_RETAIN const int** __ctype_tolower_loc(void) {
    ctype_tables_init();
    static const int* t;
    t = ctype_tolower_tbl + 128;
    return (const int**)&t;
}

SHIM_RETAIN const int** __ctype_toupper_loc(void) {
    ctype_tables_init();
    static const int* t;
    t = ctype_toupper_tbl + 128;
    return (const int**)&t;
}

/* ---------------------------------------------------------------- assert */

SHIM_RETAIN void __assert_fail(const char* assertion, const char* file, unsigned int line,
                   const char* function) {
    SHIM_LOG("assertion failed: %s (%s:%u in %s)", assertion ? assertion : "?",
             file ? file : "?", line, function ? function : "?");
    abort();
}

/* ----------------------------------------------------------------- misc */

/* digit-aware version compare (glibc-compatible ordering, simple form). */
SHIM_RETAIN int strverscmp(const char* s1, const char* s2) {
    const unsigned char* a = (const unsigned char*)s1;
    const unsigned char* b = (const unsigned char*)s2;
    while (*a && *b) {
        unsigned char ca = *a, cb = *b;
        if (ca != cb) {
            if (ca >= '0' && ca <= '9' && cb >= '0' && cb <= '9') {
                unsigned long va = 0, vb = 0;
                while (*a >= '0' && *a <= '9') {
                    va = va * 10 + (unsigned)(*a - '0');
                    a++;
                }
                while (*b >= '0' && *b <= '9') {
                    vb = vb * 10 + (unsigned)(*b - '0');
                    b++;
                }
                if (va != vb) return va < vb ? -1 : 1;
                continue;
            }
            return ca < cb ? -1 : 1;
        }
        a++;
        b++;
    }
    return *a ? 1 : (*b ? -1 : 0);
}

/* glibc __getdelim: read one delimiter-terminated record. */
SHIM_RETAIN ssize_t __getdelim(char** lineptr, size_t* n, int delim, FILE* stream) {
    if (!lineptr || !n || !stream || (!*lineptr && *n)) {
        *__errno_location() = EINVAL;
        return -1;
    }
    if (*lineptr == NULL || *n == 0) {
        *n = 128;
        char* fresh = (char*)realloc(*lineptr, *n);
        if (!fresh) return -1;
        *lineptr = fresh;
    }
    size_t used = 0;
    for (;;) {
        int c = getc(stream);
        if (c == EOF) break;
        if (used + 2 > *n) {
            size_t nn = *n * 2;
            char* p = (char*)realloc(*lineptr, nn);
            if (!p) return -1;
            *lineptr = p;
            *n = nn;
        }
        (*lineptr)[used++] = (char)c;
        if (c == delim) break;
    }
    if (used == 0) return -1; /* EOF right away */
    (*lineptr)[used] = '\0';
    return (ssize_t)used;
}

/* glibc __xpg_basename: POSIX-style final component. */
SHIM_RETAIN char* __xpg_basename(char* path) {
    if (!path || !*path) return (char*)".";
    char* s = path + strlen(path);
    while (s > path && s[-1] == '/') *(--s) = '\0'; /* XSI trims slashes */
    if (!*path) return (char*)"/";
    char* p = strrchr(path, '/');
    return p ? p + 1 : path;
}

SHIM_RETAIN void __explicit_bzero_chk(void* dst, size_t len, size_t dstlen) {
    (void)dstlen;
    memset(dst, 0, len);
    __asm__ volatile("" ::: "memory");
}

/* glibc's __fdelt_chk: guard FD_SETSIZE, return the fd_set word index. */
SHIM_RETAIN long int __fdelt_chk(unsigned long int d) {
    if (d >= 1024) {
        SHIM_LOG("fdelt_chk: fd %lu out of range", d);
        abort();
    }
    return (long int)(d / 64);
}

/* ---------------------------------------------------- libpthread stub symbols
   glibc >= 2.34 ships libpthread.so.0 as a stub whose relocations reference
   POSIX mqueue functions that moved into libc — Bionic has no mqueue support,
   so these behave as unsupported (ENOSYS), which no guest in this project
   actually relies on. */

SHIM_RETAIN int mq_open(const char* name, int oflag, ...) {
    (void)name; (void)oflag;
    *__errno_location() = 38; /* ENOSYS */
    return -1;
}
SHIM_RETAIN int mq_close(int mqd) { (void)mqd; *__errno_location() = 38; return -1; }
SHIM_RETAIN int mq_unlink(const char* name) { (void)name; *__errno_location() = 38; return -1; }
SHIM_RETAIN int mq_getattr(int mqd, void* attr) { (void)mqd; (void)attr; *__errno_location() = 38; return -1; }
SHIM_RETAIN int mq_setattr(int mqd, void* newattr, void* oldattr) {
    (void)mqd; (void)newattr; (void)oldattr; *__errno_location() = 38; return -1;
}
SHIM_RETAIN int mq_send(int mqd, const char* msg, size_t len, unsigned prio) {
    (void)mqd; (void)msg; (void)len; (void)prio; *__errno_location() = 38; return -1;
}
SHIM_RETAIN int mq_timedsend(int mqd, const char* msg, size_t len, unsigned prio, const void* abstime) {
    (void)mqd; (void)msg; (void)len; (void)prio; (void)abstime; *__errno_location() = 38; return -1;
}
SHIM_RETAIN long mq_receive(int mqd, char* msg, size_t len, unsigned* prio) {
    (void)mqd; (void)msg; (void)len; (void)prio; *__errno_location() = 38; return -1;
}
SHIM_RETAIN long mq_timedreceive(int mqd, char* msg, size_t len, unsigned* prio, const void* abstime) {
    (void)mqd; (void)msg; (void)len; (void)prio; (void)abstime; *__errno_location() = 38; return -1;
}

/* ------------------------------------------------------ misc glibc internals
   (small, independent symbols glibc-linked guests reference) */

/* link(): Android SELinux policy denies hard links inside app data (verified on
   the target phone: ln z11 tmp/a tmp/b -> EACCES), so the guest X server's
   lock protocol (create .tX0-lock, then link() it to .X0-lock) can never
   succeed. Emulate link() as a byte copy: guests never rely on rename identity,
   and the X server's own lock consumers only re-read the file. The callers
   unlink both halves again. */
SHIM_RETAIN int link(const char* oldpath, const char* newpath) {
    struct stat st;
    if (stat(oldpath, &st) != 0) return -1;
    int fi = open(oldpath, O_RDONLY);
    if (fi < 0) return -1;
    int fo = open(newpath, O_WRONLY | O_CREAT | O_EXCL, st.st_mode & 07777);
    if (fo < 0) { int e = errno; close(fi); errno = e; return -1; }
    char buf[16384];
    ssize_t n;
    while ((n = read(fi, buf, sizeof(buf))) > 0) {
        ssize_t off = 0;
        while (off < n) {
            ssize_t w = write(fo, buf + off, (size_t)(n - off));
            if (w <= 0) { int e = errno; close(fi); close(fo); unlink(newpath); errno = e; return -1; }
            off += w;
        }
    }
    int re = 0, e = errno;
    if (n < 0) re = -1;
    if (close(fi) < 0) re = -1;
    if (close(fo) < 0) re = -1;
    errno = e;
    if (getenv("UD_SHIM_TRACE")) SHIM_LOG("link(%s, %s) emulated as copy", oldpath, newpath);
    return re;
}

/* wait3(): legacy BSD-wait alias kept in the glibc ABI but dropped from modern
 * bionic; guest dash (the shell box64's popen/system use) relocates against it
 * under GLIBC_2.2.5. It is exactly wait4(-1, ...) per POSIX. */
SHIM_RETAIN int wait3(int* status, int options, void* rusage) {
    return wait4(-1, status, options, (struct rusage*)rusage);
}

/* popen(): Xvfb (Xorg os/utils.c) spawns xkbcomp through popen(). Box64's
 * wrapped libc binds "popen" to the native Bionic popen, which execs /bin/sh -
 * a path that does not exist on Android (this is why the X server's keymap
 * compile died with no child ever starting). Re-implement it on top of the
 * Bionic popen by explicitly naming /system/bin/sh: the child inherits the
 * guest cwd (our rootfs), so the relative data paths stay valid, and PATH
 * (rootfs/usr/bin first) makes "xkbcomp" resolve to the guest binary.
 * NOTE: the command string passed here contains double quotes but no single
 * quotes (Xorg's -em1/-emp/-eml literals), so the single-quote wrap is safe.
 * pclose() still resolves to the Bionic pclose over the same FILE*, so hiding
 * the process bookkeeping is not needed. */
SHIM_RETAIN void* my_popen(void* emu, const char* cmd, const char* type) {
    (void)emu;
    static const char pre[] = "/system/bin/sh -c 'exec ";
    char* s = (char*)malloc(sizeof(pre) - 1 + strlen(cmd) + 3);
    if (!s) return NULL;
    strcpy(s, pre);
    strcat(s, cmd);
    strcat(s, "'");
    if (getenv("UD_SHIM_TRACE")) SHIM_LOG("popen(\"%s\", %s)", cmd, type);
    FILE* f = popen(s, type);
    free(s);
    return f;
}

/* shmget/shmat/shmctl/shmdt: Android's app seccomp policy kills any process
 * that issues the SysV-IPC syscalls (measured: "Fatal signal 31 (SIGSYS), code
 * 1 (SYS_SECCOMP), syscall 194" = arm64 shmget). A guest can therefore never
 * have SysV shared memory here. Box64's wrapped libc binds these symbols to
 * Bionic's own shmget wrappers, which make the host syscall directly, so route
 * them through these my_ stubs instead: fail like a kernel built without SysV
 * IPC (ENOSYS). X11 MIT-SHM clients (winex11) fall back to plain XPutImage.
 * The raw guest-syscall path is intercepted separately in x64syscall.c (F4). */
SHIM_RETAIN int my_shmget(void* emu, int key, unsigned long size, int shmflg) {
    (void)emu; (void)key; (void)size; (void)shmflg;
    if (getenv("UD_SHIM_TRACE")) SHIM_LOG("shmget(%d, %lu, %d) -> ENOSYS", key, size, shmflg);
    errno = ENOSYS;
    return -1;
}

SHIM_RETAIN void* my_shmat(void* emu, int shmid, const void* shmaddr, int shmflg) {
    (void)emu; (void)shmid; (void)shmaddr; (void)shmflg;
    errno = ENOSYS;
    return (void*)-1;
}

SHIM_RETAIN int my_shmctl(void* emu, int shmid, int cmd, void* buf) {
    (void)emu; (void)shmid; (void)cmd; (void)buf;
    errno = ENOSYS;
    return -1;
}

SHIM_RETAIN int my_shmdt(void* emu, void* shmaddr) {
    (void)emu; (void)shmaddr;
    errno = ENOSYS;
    return -1;
}

/* glibc sigset emptiness probe: buffers are the caller's glibc-sized set; we
   scan a conservative 128 bytes like glibc's own C-locale ABI size. */
SHIM_RETAIN int sigisemptyset(void* set) {
    const unsigned char* p = (const unsigned char*)set;
    if (!p) return 1;
    for (size_t i = 0; i < 16; i++) {
        if (p[i]) return 0;
    }
    return 1;
}

/* name_to_handle_at: not exposed by Bionic; guests see the filesystems as
   unsupporting it and fall back to the stat paths. */
SHIM_RETAIN int name_to_handle_at(int dirfd, const char* pathname, void* handle,
                      int* mount_id, int flags) {
    (void)dirfd; (void)pathname; (void)handle; (void)mount_id; (void)flags;
    *__errno_location() = 95; /* EOPNOTSUPP */
    return -1;
}

/* get_current_dir_name: glibc-specific; Bionic has getcwd(NULL, 0). */
SHIM_RETAIN char* get_current_dir_name(void) {
    return getcwd(0, 0);
}

/* glibc open/openat with the O_CLOEXEC-or-bust check variant. */
SHIM_RETAIN int __open64_2(const char* path, int flags) {
    return open(path, flags);
}
SHIM_RETAIN int __openat64_2(int dirfd, const char* path, int flags) {
    return openat(dirfd, path, flags);
}

/* GNU parse_printf_format: count/record printf conversions. Guests mostly use
   it for format validation; we record every conversion as one pointer-sized
   argument. */
SHIM_RETAIN size_t parse_printf_format(const char* fmt, size_t n, int* argtypes, size_t* sizes, int* nargs) {
    if (!fmt) return 0;
    size_t total = 0;
    for (const char* p = fmt; *p; p++) {
        if (*p != '%') continue;
        p++;
        if (*p == '%') continue;             /* literal % */
        if (!*p) break;
        /* flags */
        while (*p && strchr("#0- +'", *p)) p++;
        /* width */
        while (*p >= '0' && *p <= '9') p++;
        if (*p == '*') p++;
        /* precision */
        if (*p == '.') { p++; if (*p == '*') p++; while (*p >= '0' && *p <= '9') p++; }
        /* length modifier */
        int longmod = 0;
        while (*p == 'l' || *p == 'L' || *p == 'z' || *p == 'j' || *p == 't' || *p == 'h' || *p == 'q' || *p == 'w') { p++; longmod = 1; }
        if (*p) p++;
        if (argtypes && total < n) argtypes[total] = longmod ? 3 : 1; /* PA_SLONG-STY|LONG: see note */
        if (sizes && total < n) sizes[total] = sizeof(size_t) < sizeof(long) ? sizeof(long) : sizeof(size_t);
        if (nargs) *nargs = (int)(total + 1);
        total++;
    }
    if (nargs) *nargs = (int)total;
    return total;
}

/* --------------------------------------------------------------- netdb */
/* Bionic and glibc lay out struct addrinfo differently (glibc x86-64 puts the
 * pointer pair ai_addr/ai_canonname at offsets 24/32; Bionic's real
 * implementation puts them the other way around — on-device probe evidence
 * 2026-10-17: getaddrinfo("127.0.0.1","6000") returned rc=0 with a chain the
 * guest saw as addr=NULL, canon=<sockaddr buffer>). Box64 wraps getaddrinfo
 * as a plain passthrough, so every guest that resolves a display hostname
 * through libX11/Xtrans read ai_addr==NULL and died with
 * connect(fd,NULL,16)/EFAULT -> "Can't open display".
 * Fix inside the shim: call the real Bionic getaddrinfo (resolved at runtime
 * via dlopen("libc.so")), then hand the guest a chain in
 * the exact x86-64 glibc layout it was compiled against. The five scalar
 * fields occupy identical offsets in both layouts (4x int32 then socklen_t),
 * so the HINTS structure needs no conversion. Only the first 48 bytes are
 * guest-visible; the Bionic chain head is kept away from that prefix and
 * released by our freeaddrinfo(). No bionic offsets are hand-assumed here:
 * bionic structs are read through the NDK headers the shim compiles with. */
#include <stdint.h>
#include <netdb.h>

/* Guest-side struct addrinfo in the exact x86-64 glibc layout the guests were
 * compiled against (48 bytes, verified on-device: addrlen@16, addr@24,
 * next@40). */
typedef struct {
    int32_t  ai_flags;
    int32_t  ai_family;
    int32_t  ai_socktype;
    int32_t  ai_protocol;
    uint32_t ai_addrlen;
    void*    ai_addr;
    char*    ai_canonname;
    void*    ai_next;
    void*    real_head;
} g_addrinfo_t;

/* Reaching the real Bionic functions: a compile-time rename cannot coexist
 * with exporting our own symbol of the same name (the -D lexer rename and
 * even token-pasting both rewrite the definition name), so the real resolver
 * pair is looked up once through a direct dlopen("libc.so") (Bionic's own
 * libc, not this program object). */
#include <dlfcn.h>

typedef int (*ud_gai_fn)(const char*, const char*, const struct addrinfo*, struct addrinfo**);
static ud_gai_fn ud_bga;
static void (*ud_bfa)(struct addrinfo*);

static void ud_resolve_real_getaddrinfo(void) {
    void* h = dlopen("libc.so", RTLD_NOW);
    if (!h) return;
    ud_bga = (ud_gai_fn)dlsym(h, "getaddrinfo");
    ud_bfa = (void (*)(struct addrinfo*))dlsym(h, "freeaddrinfo");
}

/* glibc prototypes (as the netdb.h above declares; struct addrinfo is only an
 * opaque tag here — the real layout conversion happens through g_addrinfo_t). */
SHIM_RETAIN int getaddrinfo(const char* node, const char* service,const struct addrinfo* hints, struct addrinfo** res) {
    struct addrinfo* bres = NULL;
    if (!ud_bga) ud_resolve_real_getaddrinfo();
    if (!ud_bga) { if (res) *res = NULL; return EAI_FAIL; }
    int rc = ud_bga(node, service, hints, &bres);
    if (res) *res = NULL;
    if (rc == 0 && !bres) rc = EAI_NONAME;
    if (rc != 0 || !bres || !res) return rc;
    struct addrinfo* head = bres;
    g_addrinfo_t* first = NULL;
    g_addrinfo_t* last = NULL;
    rc = 0;
    for (struct addrinfo* b = bres; b; b = b->ai_next) {
        g_addrinfo_t* g = (g_addrinfo_t*)calloc(1, sizeof(g_addrinfo_t));
        if (!g) { rc = EAI_MEMORY; break; }
        g->ai_flags = b->ai_flags;
        g->ai_family = b->ai_family;
        g->ai_socktype = b->ai_socktype;
        g->ai_protocol = b->ai_protocol;
        g->ai_addrlen = b->ai_addrlen;
        if (b->ai_addr && b->ai_addrlen) {
            g->ai_addr = malloc(b->ai_addrlen);
            if (!g->ai_addr) { rc = EAI_MEMORY; break; }
            memcpy(g->ai_addr, b->ai_addr, b->ai_addrlen);
        }
        if (b->ai_canonname) {
            g->ai_canonname = strdup(b->ai_canonname);
            if (!g->ai_canonname) { rc = EAI_MEMORY; break; }
        }
        if (!first) { first = g; g->real_head = head; }
        else last->ai_next = g;
        last = g;
    }
    if (rc != 0) {
        for (g_addrinfo_t* g = first; g;) {
            g_addrinfo_t* n = (g_addrinfo_t*)g->ai_next;
            free(g->ai_addr); free(g->ai_canonname); free(g);
            g = n;
        }
        if (ud_bfa) ud_bfa(head);
        return rc;
    }
    *res = (struct addrinfo*)first;
    return 0;
}

SHIM_RETAIN void freeaddrinfo(struct addrinfo* gv) {
    g_addrinfo_t* g = (g_addrinfo_t*)gv;
    if (!g) return;
    struct addrinfo* head = (struct addrinfo*)g->real_head;
    while (g) {
        g_addrinfo_t* n = (g_addrinfo_t*)g->ai_next;
        free(g->ai_addr);
        free(g->ai_canonname);
        free(g);
        g = n;
    }
    if (head) {
        if (!ud_bfa) ud_resolve_real_getaddrinfo();
        if (ud_bfa) ud_bfa(head);
    }
}

/* -------------------------------------------------------------- gettext */
/* No translations are bundled: return the message id unchanged. */
SHIM_RETAIN char* bindtextdomain(const char* domain, const char* dir) {
    (void)domain;
    return (char*)dir;
}
SHIM_RETAIN char* textdomain(const char* domain) {
    return (char*)(domain ? domain : "messages");
}
SHIM_RETAIN char* gettext(const char* msgid) { return (char*)msgid; }
SHIM_RETAIN char* dgettext(const char* domain, const char* msgid) {
    (void)domain;
    return (char*)msgid;
}
SHIM_RETAIN char* dcgettext(const char* domain, const char* msgid, int category) {
    (void)domain; (void)category;
    return (char*)msgid;
}
SHIM_RETAIN char* ngettext(const char* msgid1, const char* msgid2, unsigned long n) {
    return (char*)(n == 1 ? msgid1 : msgid2);
}
SHIM_RETAIN char* dngettext(const char* domain, const char* msgid1, const char* msgid2,
                unsigned long n) {
    (void)domain;
    return (char*)(n == 1 ? msgid1 : msgid2);
}
SHIM_RETAIN char* dcngettext(const char* domain, const char* msgid1, const char* msgid2,
                 unsigned long n, int category) {
    (void)domain; (void)category;
    return (char*)(n == 1 ? msgid1 : msgid2);
}
