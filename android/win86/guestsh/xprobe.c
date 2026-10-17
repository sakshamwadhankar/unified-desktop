/* xprobe.c - phone-side raw X11 client probe under box64.
 * Connects to 127.0.0.1:6000, sends the 12-byte "no auth" setup prelude,
 * waits up to 5 s and prints how many reply bytes arrived. */
#include <stdio.h>
#include <string.h>
#include <errno.h>
#include <stddef.h>
#include <inttypes.h>
#include <netdb.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>

int main(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) { fprintf(stderr, "[xprobe] socket: %s\n", strerror(errno)); return 1; }
    struct sockaddr_in sa; memset(&sa, 0, sizeof(sa));
    sa.sin_family = AF_INET;
    sa.sin_port = htons(6000);
    sa.sin_addr.s_addr = inet_addr("127.0.0.1");
    if (connect(fd, (struct sockaddr*)&sa, sizeof(sa)) != 0) {
        fprintf(stderr, "[xprobe] connect: %s\n", strerror(errno));
        return 1;
    }
    fprintf(stderr, "[xprobe] connected\n");
    unsigned char pre[12]; memset(pre, 0, sizeof(pre));
    pre[0] = 'l';
    pre[2] = 11; /* protocol-major-version */
    if (write(fd, pre, sizeof(pre)) != sizeof(pre)) {
        fprintf(stderr, "[xprobe] write: %s\n", strerror(errno));
        return 1;
    }
    fprintf(stderr, "[xprobe] prelude sent\n");
    char buf[64];
    ssize_t n = read(fd, buf, sizeof(buf));
    if (n < 0) fprintf(stderr, "[xprobe] read: %s\n", strerror(errno));
    else fprintf(stderr, "[xprobe] reply n=%zd head0=%d head1=%d\n", n, n > 0 ? (buf[0] & 0xff) : -1, n > 1 ? (buf[1] & 0xff) : -1);
    if (n > 0) {
        fprintf(stderr, "[xprobe] reply hex:");
        for (ssize_t i = 0; i < n && i < 32; i++) fprintf(stderr, " %02x", buf[i] & 0xff);
        fprintf(stderr, "\n");
    }
    close(fd);

    /* --- GAI probe: replicate libX11 Xtranssock _X11TransSocketINETConnect:
     * getaddrinfo(host digits, service string, hints UNSPEC/STREAM/TCP),
     * then walk results and connect. Box64's getaddrinfo is a passthrough to
     * bionic; we print what the guest actually reads out of each entry, byte
     * for byte, to pinpoint the ai_addr mismatch seen with guest xeyes. */
    struct addrinfo hints; memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    hints.ai_protocol = IPPROTO_TCP;
    struct addrinfo *res = NULL;
    fprintf(stderr, "[xprobe] gai: sizeof(addrinfo)=%zu ai_addrlen@%zu ai_addr@%zu ai_next@%zu\n",
            sizeof(struct addrinfo),
            offsetof(struct addrinfo, ai_addrlen),
            offsetof(struct addrinfo, ai_addr),
            offsetof(struct addrinfo, ai_next));
    int grc = getaddrinfo("127.0.0.1", "6000", &hints, &res);
    fprintf(stderr, "[xprobe] getaddrinfo rc=%d res=%p errno_str=%s\n", grc, (void*)res, strerror(errno));
    int chain = 0;
    for (struct addrinfo *a = res; a && chain < 4; a = a->ai_next, chain++) {
        fprintf(stderr, "[xprobe] gai[%d]: family=%d socktype=%d proto=%d addrlen=%" PRId32
                " addr=%p canon=%p next=%p\n",
                chain, a->ai_family, a->ai_socktype, a->ai_protocol, a->ai_addrlen,
                a->ai_addr, a->ai_canonname, a->ai_next);
        if (a->ai_addr) {
            unsigned char *ab = (unsigned char*)a->ai_addr;
            fprintf(stderr, "[xprobe] gai[%d] addr bytes:", chain);
            for (int i = 0; i < 16; i++) fprintf(stderr, " %02x", ab[i]);
            fprintf(stderr, "\n");
        }
        if (grc == 0 && a->ai_addr) {
            int fd2 = socket(a->ai_family, a->ai_socktype, a->ai_protocol);
            if (fd2 < 0) fprintf(stderr, "[xprobe] gai socket: %s\n", strerror(errno));
            else {
                int cr = connect(fd2, a->ai_addr, a->ai_addrlen);
                fprintf(stderr, "[xprobe] gai connect(fd=%d)=%d errno=%d (%s)\n", fd2, cr, errno, strerror(errno));
                close(fd2);
            }
        }
    }
    if (grc == 0) freeaddrinfo(res);
    return 0;
}
