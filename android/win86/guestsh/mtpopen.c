/* mtpopen.c - replicate xorg-server 21.1 os/utils.c Popen EXACTLY:
 *  - background pthread (like the server's input threads)
 *  - pipe, fork with ALL signals blocked, child unblocks + dup2 + execl
 *  - argv[0] = "h" (the state after our same-length "/bin/sh"->"bin/sh" patch:
 *    the "sh" literal is a suffix pointer &path[5], byte5='h' after the patch)
 *  - parent writes an include-based keymap text then closes (the server's flow)
 *  - child is bin/sh = udsh -> execv xkbcomp (no intermediate shell)
 * Prints [mtpopen] i rc=<n> and whether the .xkm landed. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <signal.h>
#include <fcntl.h>
#include <pthread.h>
#include <sys/wait.h>
#include <sys/stat.h>

static const char *CMD =
"\"usr/bin/catfR\" -w 1 \"-R.usr/share/X11/xkb\" -xkm \"-\" "
"-em1 \"The XKEYBOARD keymap compiler (xkbcomp) reports:\" "
"-emp \"> \" "
"-eml \"Errors from xkbcomp are not fatal to the X server\" "
"\".var/lib/xkb/server-0.xkm\"";

static const char *KEYMAP =
"xkb_keymap {\n"
"    xkb_keycodes  { include \"evdev\" };\n"
"    xkb_types     { include \"complete\" };\n"
"    xkb_compat    { include \"complete\" };\n"
"    xkb_symbols   { include \"pc+us\" };\n"
"    xkb_geometry  { include \"pc(pc105)\" };\n"
"};\n";

static void *bg(void *p) {
    for (;;) usleep(20000);
    return 0;
}

int main(int argc, char **argv) {
    pthread_t t;
    pthread_create(&t, NULL, bg, NULL);

    int n = (argc > 1) ? atoi(argv[1]) : 4;
    for (int i = 0; i < n; i++) {
        int in_pipe[2];
        if (pipe(in_pipe)) { printf("[mtpopen] %d pipe_err\n", i); fflush(stdout); continue; }

        sigset_t all; sigfillset(&all); sigset_t old;
        sigprocmask(SIG_BLOCK, &all, &old);
        pid_t p = fork();
        if (p == 0) {
            /* child, exactly like xorg's Popen child */
            sigprocmask(SIG_SETMASK, &old, NULL);
            close(in_pipe[1]);
            dup2(in_pipe[0], 0);
            close(in_pipe[0]);
        /* (setgid/setuid test lines removed: Android seccomp kills setgid) */
        execl("bin/sh", "h", "-c", CMD, (char *)NULL);
            _exit(127);
        }
        sigprocmask(SIG_SETMASK, &old, NULL);
        close(in_pipe[0]);

        /* stdio write path, exactly like xorg's RunXkbComp parent */
        FILE *f = fdopen(in_pipe[1], "w");
        fputs(KEYMAP, f);
        fclose(f);
        int cause = ftello ? 0 : 0; /* placeholder */
        printf("[mtpopen] %d stdio closed\n", i);
        fflush(stdout);

        int st = 0;
        waitpid(p, &st, 0);
        int rc = WIFEXITED(st) ? WEXITSTATUS(st) : -1;
        struct stat sb;
        int have = stat(".var/lib/xkb/server-:0.xkm", &sb);
        printf("[mtpopen] %d rc=%d xkm=%s size=%ld\n", i, rc,
               have == 0 ? "YES" : "no", have == 0 ? (long)sb.st_size : 0L);
        fflush(stdout);
        if (have == 0) unlink(".var/lib/xkb/server-:0.xkm");
    }
    printf("[mtpopen] done\n");
    return 0;
}
