/* Guest probe: fork+exec ON A MULTITHREADED GUEST, N times, to measure how
 * reliably box64 handles the Popen() chain Xvfb performs after the input
 * thread is up. Success = child ran bin/sh (udsh) which exec'd xkbcomp
 * -version and exited 0. Prints one line per iteration. */
#include <errno.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

static void *spin(void *arg)
{
    (void)arg;
    for (;;) usleep(10000); /* busy-ish background thread, like xorg's timer/IO threads */
    return NULL;
}

int main(void)
{
    setvbuf(stderr, NULL, _IOLBF, 0);
    pthread_t t;
    if (pthread_create(&t, NULL, spin, NULL) != 0) {
        fprintf(stderr, "[mtfork] pthread_create failed\n");
        return 1;
    }
    usleep(20000);
    int ok = 0, fail = 0;
    for (int i = 1; i <= 10; i++) {
        int pdes[2];
        if (pipe(pdes) != 0) { perror("[mtfork] pipe"); return 1; }
        pid_t pid = fork();
        if (pid < 0) { fprintf(stderr, "[mtfork] %02d fork failed: %d\n", i, errno); fail++; continue; }
        if (pid == 0) {
            close(pdes[1]);
            dup2(pdes[0], 0);
            execl("bin/sh", "sh", "-c", ".usr/bin/xkbcomp -version", NULL);
            fprintf(stderr, "[mtfork] %02d execl failed errno=%d\n", i, errno);
            _exit(127);
        }
        close(pdes[0]);
        close(pdes[1]);
        int st = 0;
        pid_t w = waitpid(pid, &st, 0);
        if (w != pid || !WIFEXITED(st) || WEXITSTATUS(st) != 0) {
            fprintf(stderr, "[mtfork] %02d FAIL w=%d st=0x%x\n", i, (int)w, st);
            fail++;
        } else {
            fprintf(stderr, "[mtfork] %02d ok\n", i);
            ok++;
        }
    }
    fprintf(stderr, "[mtfork] SUMMARY ok=%d fail=%d\n", ok, fail);
    return fail != 0;
}
