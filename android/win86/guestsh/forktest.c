/* forktest.c - reproduce the Xvfb hang pattern under box64 on the phone.
 * 1. allocate + lock/unlock a pthread mutex (heap futex state),
 * 2. fork(); child: malloc, unlock, _exit(0)   (mirrors Popen -> udsh/xkbcomp)
 * 3. parent: malloc again, then select() with a 1 s timeout in a loop.
 * If the parent wedges in a futex here, we reproduced the server hang.
 */
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
#include <stdlib.h>
#include <pthread.h>
#include <sys/select.h>
#include <sys/wait.h>

static pthread_mutex_t mtx = PTHREAD_MUTEX_INITIALIZER;

static void drain_child(int pid) {
    int st = 0;
    waitpid(pid, &st, 0);
    fprintf(stderr, "[forktest] child rc status=%d\n", st);
}

int main(void) {
    /* warm up heap like server init */
    void *p1 = malloc(64); free(p1);
    pthread_mutex_lock(&mtx);
    fprintf(stderr, "[forktest] mutex locked, forking\n");
    fflush(stderr);

    pid_t pid = fork();
    if (pid == 0) {
        /* child: Popen child analog */
        void *q = malloc(4096);
        (void)q;
        pthread_mutex_unlock(&mtx);   /* child copies of locks: release just in case */
        _exit(0);
    }
    fprintf(stderr, "[forktest] forked pid=%d\n", pid);
    fflush(stderr);
    drain_child(pid);

    /* parent: does malloc still work? */
    void *p2 = malloc(1024);
    fprintf(stderr, "[forktest] post-fork malloc ok %p\n", p2);
    fflush(stderr);
    pthread_mutex_unlock(&mtx);       /* release the pre-fork lock (parent copy) */
    free(p2);

    /* parent: does the wait machinery still run? select loop like WaitForSomething */
    int i;
    for (i = 0; i < 3; i++) {
        fd_set r;
        struct timeval tv = { 1, 0 };
        FD_ZERO(&r);
        int rc = select(1, &r, NULL, NULL, &tv);
        fprintf(stderr, "[forktest] select %d rc=%d errno=%s\n", i, rc, strerror(errno));
        fflush(stderr);
    }
    fprintf(stderr, "[forktest] DONE\n");
    return 0;
}
