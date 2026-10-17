/* Local stdio wrapper for the guest xkbcomp build (box64 run).
 *
 * Problem being fixed: glibc's <stdio.h> can already expose getc as an
 * unlocked-inline macro (the _IO_getc_unlocked path) that calls __uflow
 * when a buffer refills. Those macros read and write the glibc FILE
 * structure fields directly, which does not survive the box64 wrapped
 * stdio (bionic FILE, a different layout; __uflow not in the wrap's
 * symbol table). The result is the guest parser seeing an empty stream
 * ("syntax error: line 1 of stdin" with valid data, seen on Android
 * 2026-10-17). This header, injected first on the include path with
 * #include_next pulling in the system <stdio.h>, replaces the affected
 * macros with plain function calls (fgetc/fputc), which box64 wraps
 * correctly (fgets/fwrite never had this problem).
 */
#include_next <stdio.h>

#undef getc
#define getc(_f) xkc_fgetc(_f)
#undef getchar
#define getchar() xkc_fgetc(stdin)
#undef putc
#define putc(_c, _f) xkc_fputc(_c, _f)
#undef putchar
#define putchar(_c) xkc_fputc(_c, stdout)
