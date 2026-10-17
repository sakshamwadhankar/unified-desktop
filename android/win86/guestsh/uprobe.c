/* uprobe.c - which privilege-changing syscalls do app-uid box64/guest calls
 * get on this phone? Prints result+errno for each to stderr. */
#include <stdio.h>
#include <errno.h>
#include <unistd.h>

static void p(const char *name, long r) {
    fprintf(stderr, "[uprobe] %s -> %ld (errno=%d)\n", name, r, r ? errno : 0);
    errno = 0;
}

int main(void) {
    errno = 0;
    p("getuid", getuid());
    p("geteuid", geteuid());
    p("seteuid(getuid())", seteuid(getuid()));
    p("seteuid(euid)", seteuid(geteuid()));
    p("setegid(getgid())", setegid(getgid()));
    p("setuid(getuid())", setuid(getuid()));
    p("setgid(getgid())", setgid(getgid()));
    fprintf(stderr, "[uprobe] done\n");
    return 0;
}
