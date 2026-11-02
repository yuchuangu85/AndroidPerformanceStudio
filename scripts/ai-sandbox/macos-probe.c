#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/socket.h>
#include <sys/wait.h>
#include <unistd.h>

/* Qualification fixture only. Never receives real host data or credentials. */
int main(int argc, char **argv) {
    if (argc == 2 && strcmp(argv[1], "hello") == 0) {
        puts("APS_NATIVE_HELLO");
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "memory") == 0) {
        struct rlimit limit = {64 * 1024 * 1024, 64 * 1024 * 1024};
        int result = setrlimit(RLIMIT_AS, &limit);
        printf("setrlimit_as=%d errno=%d\n", result, result ? errno : 0);
        void *memory = mmap(NULL, 128 * 1024 * 1024, PROT_READ | PROT_WRITE,
                            MAP_PRIVATE | MAP_ANON, -1, 0);
        if (memory == MAP_FAILED) {
            puts("APS_MEMORY_DENIED");
            return result == 0 ? 0 : 2;
        }
        memset(memory, 1, 128 * 1024 * 1024);
        puts("APS_MEMORY_LIMIT_NOT_ENFORCED");
        munmap(memory, 128 * 1024 * 1024);
        return 3;
    }
    if (argc != 5 || strcmp(argv[1], "boundary") != 0) return 64;
    int failed = 0;
    int fd = open(argv[2], O_RDONLY);
    printf("host_read=%s\n", fd < 0 ? "denied" : "ALLOWED");
    if (fd >= 0) { close(fd); failed = 1; }
    fd = open(argv[2], O_WRONLY);
    printf("host_write=%s\n", fd < 0 ? "denied" : "ALLOWED");
    if (fd >= 0) { close(fd); failed = 1; }
    fd = open(argv[3], O_CREAT | O_WRONLY | O_EXCL, 0600);
    printf("scratch_write=%s\n", fd >= 0 ? "allowed" : "DENIED");
    if (fd < 0) failed = 1;
    else { close(fd); unlink(argv[3]); }
    int sock = socket(AF_INET, SOCK_STREAM, 0);
    int connected = -1;
    if (sock >= 0) {
        struct sockaddr_in address = {0};
        address.sin_family = AF_INET;
        address.sin_port = htons((unsigned short)atoi(argv[4]));
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        connected = connect(sock, (struct sockaddr *)&address, sizeof(address));
        close(sock);
    }
    printf("network=%s\n", connected < 0 ? "denied" : "ALLOWED");
    if (connected == 0) failed = 1;
    pid_t child = fork();
    if (child == 0) _exit(0);
    printf("fork=%s\n", child < 0 ? "denied" : "ALLOWED");
    if (child > 0) { waitpid(child, NULL, 0); failed = 1; }
    return failed;
}
