/*
 * unified-desktop guest "sh": a minimal stand-in for the shell that Xvfb's
 * os/utils.c Popen() execs (execl("bin/sh", "sh", "-c", program)).
 *
 * Why not dash: Debian dash under box64 aborts during its own init with
 * "sh: 0: Illegal number: 1" (exit 2) on this phone, killing every command
 * chain, including the X server's keymap compilation. The only shell use
 * Xvfb actually makes is the fixed-format xkbcomp command built by
 * xkb/ddxLoad.c RunXkbComp():
 *
 *   "w{dir}xkbcomp" -w N -R{basedir} -xkm "-" -em1 S1 -emp S2 -eml S3 "{outdir}{keymap}.xkm"
 *
 * with every token that contains special characters double-quoted. So this
 * binary (installed as the guest bin/sh) tokenizes the -c string (identical
 * semantics to dash parsing here: whitespace-separated words, "..." groups,
 * no $-expansion needed - the server passes literal data), converts it to a
 * plain argv and execv()s the first token. No nested fork/exec chain.
 *
 * Installing a second copy as bin/dash keeps real dash available for direct
 * guest debugging.
 *
 * Build on the laptop (WSL, native x86-64 gcc) - the guest links against the
 * glibc inside our extracted rootfs, which box64 also uses for every other
 * guest (dash, xkbcomp, Xvfb), so the same dynamic linking applies:
 *   gcc -Os udsh.c -o udsh
 * PT_INTERP stays /lib64/ld-linux-x86-64.so.2; box64 resolves it through
 * BOX64_LD_LIBRARY_PATH (rootfs/lib/x86_64-linux-gnu).
 */
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* Tokenize a shell-like string: split on spaces/tabs, strip surrounding
 * double quotes (the format Xvfb generates). Returns argv array ending in
 * NULL; argv[0] is the program token. The array is malloc'd as one block. */
static char **tokenize(const char *s)
{
    char **av = NULL;
    size_t nav = 0, cap = 0;
    size_t buflen = strlen(s) + 1;
    char *buf = malloc(buflen);
    size_t used = 0;
    if (!buf) return NULL;

    while (*s) {
        while (*s == ' ' || *s == '\t') s++;
        if (!*s) break;
        int quoted = 0;
        if (*s == '"') { quoted = 1; s++; }
        char *start = buf + used;
        while (*s && (quoted ? (*s != '"') : (*s != ' ' && *s != '\t'))) {
            buf[used++] = *s++;
        }
        if (quoted && *s == '"') s++;
        else if (quoted) goto bad; /* unterminated quote */
        buf[used++] = '\0';
        if (nav + 1 >= cap) {
            cap = cap ? cap * 2 : 8;
            av = realloc(av, cap * sizeof(char *));
            if (!av) { free(buf); return NULL; }
        }
        av[nav++] = start;
    }
    if (!nav) { free(buf); free(av); return NULL; }
    av = realloc(av, (nav + 1) * sizeof(char *));
    av[nav] = NULL;
    return av;
bad:
    free(buf); free(av);
    return NULL;
}

int main(int argc, char **argv)
{
    /* Debug: when rootfs/udsh-tee-on exists (cwd is the server's cwd when we
     * run under Xvfb's Popen child), copy everything on fd 0 (the keymap
     * source the server writes us) to files/tmp/udshin, log its size and a
     * hex head to stderr, then re-serve fd 0 from the copy so the exec'd
     * program still sees the full stream. */
    if (!access("udsh-tee-on", 0)) {
        int rf0 = -1;
        char tmpbuf[65536];
        size_t total = 0;
        FILE *f = fopen("tmp/udshin", "wb");
        if (f) {
            for (;;) {
                ssize_t n = read(0, tmpbuf, sizeof(tmpbuf));
                if (n <= 0)
                    break;
                fwrite(tmpbuf, 1, (size_t)n, f);
                total += (size_t)n;
            }
            fclose(f);
            fprintf(stderr, "[udsh] tee: %zu bytes to tmp/udshin\n", total);
            size_t maybe = total < 48 ? total : 48;
            f = fopen("tmp/udshin", "rb");
            if (f) {
                char head[48] = {0};
                size_t rd = fread(head, 1, maybe, f);
                fprintf(stderr, "[udsh] tee: head=");
                for (size_t i = 0; i < rd; i++)
                    fprintf(stderr, "%02x", (unsigned char)head[i]);
                fprintf(stderr, "\n[udsh] tee: head ascii=%.48s|\n", head);
                fclose(f);
            }
            rf0 = open("tmp/udshin", O_RDONLY);
            if (rf0 >= 0) {
                dup2(rf0, 0);
                close(rf0);
            } else {
                fprintf(stderr, "udsh: tee cannot reopen tmp/udshin: %s\n", strerror(errno));
            }
        } else {
            fprintf(stderr, "udsh: tee cannot open tmp/udshin: %s\n", strerror(errno));
        }
    }

    /* Trace the exact command we received (env-gated). */
    if (getenv("UD_SHIM_TRACE")) {
        for (int i = 0; i < argc; i++)
            fprintf(stderr, "[udsh] argv[%d]=%s\n", i, argv[i] ? argv[i] : "(nil)");
    }

    /* Expected shape: sh -c "<command>" */
    if (argc >= 3 && !strcmp(argv[1], "-c") && argv[2]) {
        char **av = tokenize(argv[2]);
        if (!av) {
            fprintf(stderr, "udsh: cannot tokenize command\n");
            return 2;
        }
        execv(av[0], av);
        fprintf(stderr, "udsh: execv %s failed: %s\n", av[0], strerror(errno));
        return 127;
    }

    /* Fallback for any other invocation shape: try real dash. */
    char **av2 = malloc((argc + 2) * sizeof(char *));
    av2[0] = "bin/dash";
    for (int i = 0; i < argc; i++) av2[i + 1] = argv[i];
    av2[argc + 1] = NULL;
    if (argc > 0) av2[1] = NULL; /* exec dash as "dash <args...>" */
    execv("bin/dash", av2);
    fprintf(stderr, "udsh: no -c and dash fallback failed: %s\n", strerror(errno));
    return 127;
}
